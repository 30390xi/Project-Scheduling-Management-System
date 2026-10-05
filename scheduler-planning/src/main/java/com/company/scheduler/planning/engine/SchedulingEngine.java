package com.company.scheduler.planning.engine;

import com.company.scheduler.shared.contract.*;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.planning.capacity.CapacityQueryService;
import com.company.scheduler.planning.ledger.*;
import com.company.scheduler.planning.support.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulingEngine implements PlanningService, PlanningWork {
    public record Input(UUID actorId, List<UUID> roots) {}
    public record Output(List<TaskPlanningPort.Task> tasks, List<DayPlanner.Fragment> fragments,
                         List<UUID> releaseIds, List<DayPlanner.Conflict> conflicts) {}
    public record Plan(UUID id, UUID jobId, String status, long version, long inputRevision,
                       Instant expiresAt, Output output) {}
    private record Stored(Plan plan, Input input, Long commitRevision) {}
    private final JdbcTemplate jdbc;
    private final CompanyAccess access;
    private final ObjectProvider<TaskPlanningPort> taskProvider;
    private final ObjectProvider<JobDispatcher> jobProvider;
    private final PlanningGuardRepository guard;
    private final CapacityQueryService capacity;
    private final CapacityLedgerService ledger;
    private final CompanyRepository companies;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    public SchedulingEngine(JdbcTemplate jdbc, CompanyAccess access, ObjectProvider<TaskPlanningPort> taskProvider,
            ObjectProvider<JobDispatcher> jobProvider, PlanningGuardRepository guard, CapacityQueryService capacity,
            CapacityLedgerService ledger, CompanyRepository companies, IdempotencyService idempotency, ObjectMapper mapper) {
        this.jdbc = jdbc; this.access = access; this.taskProvider = taskProvider; this.jobProvider = jobProvider;
        this.guard = guard; this.capacity = capacity; this.ledger = ledger; this.companies = companies;
        this.idempotency = idempotency; this.mapper = mapper;
    }
    private TaskPlanningPort tasks() {
        var port = taskProvider.getIfAvailable();
        if (port == null) throw error("TASK_SERVICE_UNAVAILABLE", "任务服务不可用");
        return port;
    }
    @Transactional
    public SimulationJob requestSimulation(UUID actor, UUID company, List<UUID> roots) {
        var member = access.requireMember(actor, company);
        long revision = guard.lockGuard(company);
        tasks().load(actor, company, roots); // authorization before persisting input or returning identifiers
        UUID plan = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO schedule_plan(company_id, id, status, input_revision, root_task_ids, input_snapshot,
                algorithm_version, created_by, expires_at)
            VALUES (?, ?, 'GENERATING', ?, ?::jsonb, ?::jsonb, 'day-beam-v1', ?, now() + interval '30 minutes')
            """, company, plan, revision, json(roots), json(new Input(actor, List.copyOf(roots))), member.memberId());
        var dispatcher = jobProvider.getIfAvailable();
        if (dispatcher == null) throw error("JOB_SERVICE_UNAVAILABLE", "可靠作业服务不可用");
        UUID job = dispatcher.enqueue(company, "SCHEDULE_SIMULATION", plan, 0, "simulate:" + plan);
        jdbc.update("UPDATE schedule_plan SET job_id = ? WHERE company_id = ? AND id = ?", job, company, plan);
        return new SimulationJob(job);
    }
    @Transactional
    public void generate(UUID company, UUID planId) {
        guard.lockGuard(company);
        Stored stored = read(company, planId);
        if (!stored.plan().status().equals("GENERATING")) return;
        access.requireMember(stored.input().actorId(), company);
        Output output = calculate(stored.input(), company);
        jdbc.update("""
            UPDATE schedule_plan SET status = ?, result_snapshot = ?::jsonb, input_revision = ?,
                version = version + 1, updated_at = now() WHERE company_id = ? AND id = ?
            """, output.conflicts().isEmpty() ? "READY" : "INFEASIBLE", json(output),
                guard.currentRevision(company), company, planId);
    }
    @Transactional(readOnly = true)
    public Plan get(UUID actor, UUID company, UUID planId) {
        access.requireMember(actor, company);
        Stored stored = read(company, planId);
        tasks().load(actor, company, stored.input().roots());
        return stored.plan();
    }
    @Transactional(readOnly = true)
    public Plan byJob(UUID actor, UUID company, UUID jobId) {
        access.requireMember(actor, company);
        var ids = jdbc.queryForList("SELECT id FROM schedule_plan WHERE company_id = ? AND job_id = ?", UUID.class, company, jobId);
        if (ids.isEmpty()) throw BusinessException.notFound();
        return get(actor, company, ids.getFirst());
    }
    @Transactional
    public CommitResult commit(UUID actor, UUID company, UUID planId, long version, String key) {
        var member = access.requireMember(actor, company);
        if (key == null || key.isBlank() || key.length() > 128) throw error("IDEMPOTENCY_KEY_REQUIRED", "请提供有效幂等键");
        guard.lockGuard(company);
        Stored stored = read(company, planId);
        tasks().load(actor, company, stored.input().roots());
        var begin = idempotency.begin(company, member.memberId(), "plan-commit", key,
                IdempotencyService.requestHash(planId + ":" + version));
        if (begin.outcome() == IdempotencyService.Outcome.REPLAYED) return convert(begin.replayedResponse().toString(), CommitResult.class);
        if (stored.plan().status().equals("COMMITTED")) {
            var result = new CommitResult(planId, stored.commitRevision());
            idempotency.succeed(company, member.memberId(), "plan-commit", key, mapper.valueToTree(result));
            return result;
        }
        if (!stored.plan().status().equals("READY")) throw error("PLAN_NOT_READY", "方案不可提交，请先处理冲突");
        if (stored.plan().version() != version || stored.plan().inputRevision() != guard.currentRevision(company)
                || !stored.plan().expiresAt().isAfter(Instant.now())) throw error("PLAN_STALE", "方案过期或输入已变更，请重新模拟");
        Output current = calculate(new Input(actor, stored.input().roots()), company);
        if (!current.equals(stored.plan().output())) throw error("PLAN_STALE", "成员、任务、日期或约束已变更，请重新模拟");
        Map<UUID, UUID> assignees = new TreeMap<>();
        current.fragments().forEach(f -> assignees.put(f.taskId(), f.memberId()));
        // Guard is held before any task row lock; validate permissions and versions before ledger writes.
        for (var task : current.tasks().stream().sorted(Comparator.comparing(TaskPlanningPort.Task::id)).toList()) {
            if (assignees.containsKey(task.id())) tasks().assign(actor, company, task.id(), task.version(), assignees.get(task.id()));
        }
        var reservations = current.fragments().stream().map(f -> new CapacityLedgerService.Reservation(
                f.taskId(), f.memberId(), planId, f.date(), f.minutes())).toList();
        jdbc.update("UPDATE task_allocation SET occupancy_state = 'RETAINED' WHERE company_id = ? AND work_date < ? AND occupancy_state = 'RESERVED'",
                company, companies.today(company));
        ledger.apply(company, reservations, current.releaseIds());
        long revision = guard.currentRevision(company);
        jdbc.update("""
            UPDATE schedule_plan SET status = 'COMMITTED', committed_at = now(), commit_revision = ?,
                version = version + 1, updated_at = now() WHERE company_id = ? AND id = ?
            """, revision, company, planId);
        var result = new CommitResult(planId, revision);
        idempotency.succeed(company, member.memberId(), "plan-commit", key, mapper.valueToTree(result));
        return result;
    }
    public record AllocationView(UUID taskId, UUID memberId, LocalDate date, int minutes,
                                 String occupancyState, boolean executionStarted) {}
    @Transactional(readOnly = true)
    public List<AllocationView> memberSchedule(UUID actor, UUID company, UUID memberId, LocalDate from, LocalDate to) {
        var caller = access.requireMember(actor, company);
        com.company.scheduler.planning.support.PlanningPermissions.requireSelfOrManager(caller, memberId);
        if (from == null || to == null || from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 180)
            throw error("INVALID_DATE_RANGE", "查询范围必须在 180 天以内");
        var rows = jdbc.query("""
            SELECT task_id, member_id, work_date, minutes, occupancy_state, execution_started
            FROM task_allocation WHERE company_id = ? AND member_id = ? AND work_date BETWEEN ? AND ?
              AND occupancy_state <> 'RELEASED' ORDER BY work_date, task_id
            """, (rs, i) -> new AllocationView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, LocalDate.class), rs.getInt(4), rs.getString(5), rs.getBoolean(6)), company, memberId, from, to);
        var visible = new HashSet<UUID>();
        for (UUID id : rows.stream().map(AllocationView::taskId).distinct().toList()) {
            try { tasks().load(actor, company, List.of(id)); visible.add(id); }
            catch (BusinessException denied) { if (denied.status() != HttpStatus.NOT_FOUND) throw denied; }
        }
        return rows.stream().filter(r -> visible.contains(r.taskId())).toList();
    }
    @Transactional
    public void setLocked(UUID actor, UUID company, UUID taskId, boolean locked, String reason) {
        var member = access.requireMember(actor, company);
        if (reason == null || reason.isBlank()) throw error("REASON_REQUIRED", "锁定变更必须填写原因");
        guard.lockGuard(company);
        tasks().requireManager(actor, company, taskId);
        jdbc.update("""
            INSERT INTO task_plan_lock(company_id, task_id, locked, reason, changed_by) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (company_id, task_id) DO UPDATE SET locked = EXCLUDED.locked,
                reason = EXCLUDED.reason, changed_by = EXCLUDED.changed_by, updated_at = now()
            """, company, taskId, locked, reason, member.memberId());
        guard.bumpRevision(company);
    }
    private Output calculate(Input input, UUID company) {
        var snapshots = tasks().load(input.actorId(), company, input.roots()).stream()
                .sorted(Comparator.comparing(TaskPlanningPort.Task::id)).toList();
        LocalDate today = companies.today(company), horizon = today.plusDays(179);
        Map<UUID, LocalDate> fixedFinishes = new HashMap<>();
        var active = new ArrayList<TaskPlanningPort.Task>();
        var conflicts = new ArrayList<DayPlanner.Conflict>();
        var releases = new ArrayList<UUID>();
        Map<UUID, Map<LocalDate, Integer>> restored = new HashMap<>();
        for (var task : snapshots) {
            if (task.status().equals("DONE")) { fixedFinishes.put(task.id(), task.completedDate()); continue; }
            boolean locked = Boolean.TRUE.equals(jdbc.query("SELECT locked FROM task_plan_lock WHERE company_id = ? AND task_id = ?",
                    rs -> rs.next() ? rs.getBoolean(1) : false, company, task.id()));
            if (locked || !task.movable()) {
                var dates = jdbc.queryForList("SELECT max(work_date) FROM task_allocation WHERE company_id = ? AND task_id = ? AND occupancy_state = 'RESERVED'",
                        LocalDate.class, company, task.id());
                LocalDate finish = dates.getFirst();
                if (!task.scheduleStatus().equals("PLANNED") || finish == null || finish.isBefore(today)) {
                    conflicts.add(new DayPlanner.Conflict(task.id(), "FROZEN_PLAN_INVALID", "锁定任务或前置缺少有效计划，需先处理该任务"));
                } else fixedFinishes.put(task.id(), finish);
                continue;
            }
            active.add(task);
            jdbc.query("""
                SELECT id, member_id, work_date, minutes FROM task_allocation
                WHERE company_id = ? AND task_id = ? AND occupancy_state = 'RESERVED'
                  AND NOT execution_started AND work_date >= ? ORDER BY id
                """, rs -> {
                releases.add(rs.getObject("id", UUID.class));
                restored.computeIfAbsent(rs.getObject("member_id", UUID.class), k -> new HashMap<>())
                        .merge(rs.getObject("work_date", LocalDate.class), rs.getInt("minutes"), Integer::sum);
            }, company, task.id(), today);
        }
        var candidates = new HashMap<UUID, List<UUID>>();
        var free = new HashMap<UUID, Map<LocalDate, Integer>>();
        for (var task : active) {
            var eligible = tasks().eligibleProjectMembers(input.actorId(), company, task.projectId()).stream()
                    .filter(id -> task.fixedAssignee() == null || task.fixedAssignee().equals(id))
                    .filter(id -> !task.status().equals("IN_PROGRESS") || id.equals(task.currentAssignee()))
                    .filter(id -> qualified(company, id, task.skills())).sorted().toList();
            candidates.put(task.id(), eligible);
            for (UUID id : eligible) if (!free.containsKey(id)) {
                var days = new TreeMap<LocalDate, Integer>();
                for (var day : capacity.capacity(company, id, today, horizon)) days.put(day.workDate(), day.remainingMinutes()
                        + restored.getOrDefault(id, Map.of()).getOrDefault(day.workDate(), 0));
                free.put(id, days);
            }
        }
        if (conflicts.isEmpty()) {
            var result = new DayPlanner().solve(active, candidates, free, fixedFinishes, today, horizon);
            releases.sort(UUID::compareTo);
            return new Output(snapshots, result.fragments(), releases, result.conflicts());
        }
        return new Output(snapshots, List.of(), List.of(), conflicts);
    }
    private boolean qualified(UUID company, UUID member, List<TaskCatalog.SkillRequirement> requirements) {
        for (var skill : requirements) {
            Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM member_skill ms JOIN skill s ON s.company_id = ms.company_id AND s.id = ms.skill_id
                WHERE ms.company_id = ? AND ms.member_id = ? AND s.code = ? AND s.active
                  AND NOT ms.self_reported AND ms.certified_by IS NOT NULL AND ms.level >= ?
                """, Integer.class, company, member, skill.code(), skill.minimumLevel());
            if (count == null || count == 0) return false;
        }
        return true;
    }
    private Stored read(UUID company, UUID plan) {
        return jdbc.query("SELECT * FROM schedule_plan WHERE company_id = ? AND id = ?", (rs, i) -> new Stored(
                new Plan(plan, rs.getObject("job_id", UUID.class), rs.getString("status"), rs.getLong("version"),
                        rs.getLong("input_revision"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("result_snapshot") == null ? null : convert(rs.getString("result_snapshot"), Output.class)),
                convert(rs.getString("input_snapshot"), Input.class), (Long) rs.getObject("commit_revision")), company, plan)
                .stream().findFirst().orElseThrow(BusinessException::notFound);
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T convert(String value, Class<T> type) { try { return mapper.readValue(value, type); } catch (Exception e) { throw new IllegalStateException(e); } }
    private BusinessException error(String code, String message) { return new BusinessException(HttpStatus.CONFLICT, code, message); }
}
