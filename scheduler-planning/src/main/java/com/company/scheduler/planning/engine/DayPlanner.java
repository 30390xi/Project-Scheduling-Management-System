package com.company.scheduler.planning.engine;

import com.company.scheduler.shared.contract.TaskPlanningPort.Task;
import java.time.LocalDate;
import java.util.*;

/** Bounded deterministic beam search. Capacity is supplied by the authoritative ledger/calendar. */
public final class DayPlanner {
    public record Fragment(UUID taskId, UUID memberId, LocalDate date, int minutes) {}
    public record Conflict(UUID taskId, String code, String message) {}
    public record Result(List<Fragment> fragments, List<Conflict> conflicts) {}
    private record State(List<Fragment> fragments, Map<UUID, LocalDate> finishes,
                         Map<UUID, Map<LocalDate, Integer>> free) {}
    private record Option(List<Fragment> fragments, LocalDate start, LocalDate finish, boolean preferred) {}
    private static final int BEAM_WIDTH = 16;

    public Result solve(List<Task> tasks, Map<UUID, List<UUID>> candidates,
            Map<UUID, Map<LocalDate, Integer>> capacity, Map<UUID, LocalDate> fixedFinishes,
            LocalDate today, LocalDate horizon) {
        var remaining = new ArrayList<>(tasks);
        remaining.sort(Comparator.comparing((Task t) -> t.hardDeadline() == null ? LocalDate.MAX : t.hardDeadline())
                .thenComparing(Task::priority).thenComparing(Task::id));
        List<State> beam = List.of(new State(List.of(), new HashMap<>(fixedFinishes), copy(capacity)));
        while (!remaining.isEmpty()) {
            Task task = remaining.stream().filter(t -> beamReady(t, remaining)).findFirst().orElse(null);
            if (task == null) return conflict(null, "DEPENDENCY_CYCLE", "依赖图存在环路");
            if (!Set.of("READY", "IN_PROGRESS").contains(task.status()) || task.minutes() <= 0) {
                return conflict(task.id(), "TASK_NOT_READY", "任务需确认工时、解除阻塞后才能排期");
            }
            List<UUID> members = candidates.getOrDefault(task.id(), List.of());
            if (members.isEmpty()) return conflict(task.id(), "NO_SKILLED_MEMBER", "没有满足技能、项目范围及指定负责人条件的在职成员");
            var nextBeam = new ArrayList<State>();
            for (State state : beam) {
                LocalDate earliest = task.earliestStart() == null || task.earliestStart().isBefore(today) ? today : task.earliestStart();
                if ("IN_PROGRESS".equals(task.status()) && !earliest.isAfter(today)) earliest = today.plusDays(1);
                boolean missing = false;
                for (UUID previous : task.predecessors()) {
                    LocalDate finish = state.finishes().get(previous);
                    if (finish == null) { missing = true; break; }
                    if (!earliest.isAfter(finish)) earliest = finish.plusDays(1);
                }
                if (missing) continue;
                LocalDate last = task.hardDeadline() == null || task.hardDeadline().isAfter(horizon) ? horizon : task.hardDeadline();
                var options = new ArrayList<Option>();
                for (UUID member : members) {
                    var fragments = new ArrayList<Fragment>();
                    int left = task.minutes();
                    var days = state.free().getOrDefault(member, Map.of());
                    for (LocalDate date = earliest; !date.isAfter(last) && left > 0; date = date.plusDays(1)) {
                        int minutes = Math.min(left, Math.max(0, days.getOrDefault(date, 0)));
                        if (minutes > 0) { fragments.add(new Fragment(task.id(), member, date, minutes)); left -= minutes; }
                    }
                    if (left == 0) options.add(new Option(fragments, fragments.getFirst().date(),
                            fragments.getLast().date(), member.equals(task.preferredMember())));
                }
                options.sort(Comparator.comparing(Option::start).thenComparing(Option::finish)
                        .thenComparing(o -> !o.preferred()).thenComparing(o -> o.fragments().getFirst().memberId()));
                for (Option option : options) {
                    var free = copy(state.free());
                    var fragments = new ArrayList<>(state.fragments());
                    fragments.addAll(option.fragments());
                    for (var fragment : option.fragments()) free.get(fragment.memberId()).merge(fragment.date(), -fragment.minutes(), Integer::sum);
                    var finishes = new HashMap<>(state.finishes());
                    finishes.put(task.id(), option.finish());
                    nextBeam.add(new State(fragments, finishes, free));
                }
            }
            if (nextBeam.isEmpty()) return conflict(task.id(), "NO_FEASIBLE_PLAN_IN_SEARCH",
                    "当前依赖、容量、硬截止和搜索范围内未找到可行方案；可调整成员或截止后重试");
            // Keep alternatives for downstream constraints; insertion order favors earliest start and completion.
            beam = List.copyOf(nextBeam.subList(0, Math.min(BEAM_WIDTH, nextBeam.size())));
            remaining.remove(task);
        }
        return new Result(beam.getFirst().fragments(), List.of());
    }
    private static boolean beamReady(Task task, List<Task> remaining) {
        return remaining.stream().noneMatch(p -> task.predecessors().contains(p.id()));
    }
    private static Result conflict(UUID id, String code, String message) { return new Result(List.of(), List.of(new Conflict(id, code, message))); }
    private static Map<UUID, Map<LocalDate, Integer>> copy(Map<UUID, Map<LocalDate, Integer>> original) {
        var result = new HashMap<UUID, Map<LocalDate, Integer>>();
        original.forEach((id, days) -> result.put(id, new TreeMap<>(days)));
        return result;
    }
}
