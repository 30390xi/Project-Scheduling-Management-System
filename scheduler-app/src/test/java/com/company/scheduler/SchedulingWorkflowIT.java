package com.company.scheduler;

import static org.assertj.core.api.Assertions.*;
import com.company.scheduler.task.service.*;
import com.company.scheduler.task.api.dto.*;
import com.company.scheduler.task.domain.*;
import com.company.scheduler.planning.engine.SchedulingEngine;
import com.company.scheduler.agent.job.JobRunner;
import com.company.scheduler.shared.error.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scheduler.agent.jobs.enabled=false", "scheduler.agent.outbox.enabled=false"})
class SchedulingWorkflowIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskService tasks;
    @Autowired TaskTransitionService transitions;
    @Autowired DependencyService dependencies;
    @Autowired SchedulingEngine engine;
    @Autowired JobRunner runner;
    @Autowired com.company.scheduler.agent.ai.AnalysisConfirmationService confirmations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.company.scheduler.shared.contract.AiModelGateway gateway;
    @org.junit.jupiter.api.BeforeEach void disabledByDefault() {
        org.mockito.Mockito.when(gateway.analyze(org.mockito.ArgumentMatchers.any())).thenThrow(
            new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"AI_NOT_CONFIGURED","test manual fallback"));
    }
    record Fixture(UUID company, UUID project, UUID user, UUID first, UUID second, LocalDate today) {}
    Fixture fixture() {
        UUID c=UUID.randomUUID(), p=UUID.randomUUID(), user=UUID.randomUUID(), other=UUID.randomUUID(), a=UUID.randomUUID(), b=UUID.randomUUID();
        jdbc.update("INSERT INTO company(id,name) VALUES (?, 'scheduler test')", c);
        jdbc.update("INSERT INTO planning_guard(company_id) VALUES (?)", c);
        jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,'unused'),(?,?,'unused')", user,"a"+user,other,"b"+other);
        jdbc.update("INSERT INTO company_member(id,company_id,user_id,role) VALUES (?,?,?,'ADMIN'),(?,?,?,'MEMBER')", a,c,user,b,c,other);
        jdbc.update("INSERT INTO project(id,company_id,name) VALUES (?,?,'project')", p,c);
        jdbc.update("INSERT INTO project_member(company_id,project_id,member_id,role) VALUES (?,?,?,'OWNER'),(?,?,?,'MEMBER')",c,p,a,c,p,b);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        // Tests remain deterministic on weekends; these are explicit working calendar fixtures.
        for(int i=0;i<5;i++) jdbc.update("INSERT INTO calendar_day(company_id,work_date,workday_flag,source) VALUES (?,?,true,'MANUAL')",c,today.plusDays(i));
        return new Fixture(c,p,user,a,b,today);
    }
    TaskDetail ready(Fixture f, int minutes, UUID fixed, UUID preferred, List<SkillRequirementDto> skills) {
        var task=tasks.create(f.user(),f.company(),new CreateTaskRequest(f.project(),null,"Test task","Deliver task","BACKEND",TaskPriority.P2,
                null,null,f.today(),minutes,fixed,preferred,"Accepted",skills),null);
        var submitted=tasks.submit(f.user(),f.company(),task.id(),task.version());
        return transitions.transition(f.user(),f.company(),task.id(),new TransitionRequest(TransitionAction.MARK_READY,null,null,null),submitted.version());
    }
    SchedulingEngine.Plan simulate(Fixture f, UUID... ids) {
        var job=engine.requestSimulation(f.user(),f.company(),List.of(ids));
        for(int i=0;i<30;i++) {
            runner.pollOnce();
            var plan=engine.byJob(f.user(),f.company(),job.jobId());
            if(!plan.status().equals("GENERATING")) return plan;
        }
        throw new AssertionError("simulation did not terminate");
    }
    void commit(Fixture f,SchedulingEngine.Plan plan) { engine.commit(f.user(),f.company(),plan.id(),plan.version(),"commit-"+plan.id()); }
    int occupied(Fixture f,UUID member,LocalDate date) {
        return jdbc.queryForObject("SELECT COALESCE(sum(occupied_minutes),0) FROM member_day_capacity WHERE company_id=? AND member_id=? AND work_date=?",Integer.class,f.company(),member,date);
    }
    @Test void fullMemberIsReassignedTodayAndSimulationDoesNotReserve() {
        var f=fixture(); var full=ready(f,360,f.first(),null,List.of()); commit(f,simulate(f,full.id()));
        var next=ready(f,120,null,f.first(),List.of()); var plan=simulate(f,next.id());
        assertThat(plan.status()).isEqualTo("READY");
        assertThat(plan.output().fragments()).singleElement().satisfies(fragment->{
            assertThat(fragment.memberId()).isEqualTo(f.second()); assertThat(fragment.date()).isEqualTo(f.today());
        });
        assertThat(occupied(f,f.second(),f.today())).isZero();
        commit(f,plan); assertThat(occupied(f,f.second(),f.today())).isEqualTo(120);
        assertThat(engine.memberSchedule(f.user(),f.company(),f.second(),f.today(),f.today())).hasSize(1);
    }
    @Test void dailyDependencyAndFutureStartAreEnforced() {
        var f=fixture(); var first=ready(f,60,f.first(),null,List.of()); var next=ready(f,60,f.second(),null,List.of());
        dependencies.replacePredecessors(f.user(),f.company(),next.id(),new DependencyUpdateRequest(List.of(first.id())),next.version());
        var plan=simulate(f,first.id(),next.id()); assertThat(plan.status()).isEqualTo("READY"); commit(f,plan);
        var nextFragment=plan.output().fragments().stream().filter(x->x.taskId().equals(next.id())).findFirst().orElseThrow();
        assertThat(nextFragment.date()).isEqualTo(f.today().plusDays(1));
        assertThatThrownBy(()->transition(f,next.id(),TransitionAction.START)).hasFieldOrPropertyWithValue("code","PREDECESSOR_NOT_DONE");
        transition(f,first.id(),TransitionAction.START); transition(f,first.id(),TransitionAction.SUBMIT_REVIEW); transition(f,first.id(),TransitionAction.ACCEPT);
        assertThatThrownBy(()->transition(f,next.id(),TransitionAction.START)).hasFieldOrPropertyWithValue("code","NO_ALLOCATION_TODAY");
    }
    TaskDetail transition(Fixture f,UUID id,TransitionAction action) {
        var task=tasks.detail(f.user(),f.company(),id);
        return transitions.transition(f.user(),f.company(),id,new TransitionRequest(action,"integration",null,"integration"),task.version());
    }
    @Test void completionRetainsStartedTodayAndReleasesFutureAndCancelReleasesUnstarted() {
        var f=fixture(); var task=ready(f,480,f.first(),null,List.of()); commit(f,simulate(f,task.id()));
        assertThat(occupied(f,f.first(),f.today())).isEqualTo(360);
        assertThat(occupied(f,f.first(),f.today().plusDays(1))).isEqualTo(120);
        transition(f,task.id(),TransitionAction.START); transition(f,task.id(),TransitionAction.SUBMIT_REVIEW); transition(f,task.id(),TransitionAction.ACCEPT);
        assertThat(occupied(f,f.first(),f.today())).isEqualTo(360);
        assertThat(occupied(f,f.first(),f.today().plusDays(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT occupancy_state FROM task_allocation WHERE company_id=? AND task_id=? AND work_date=?",String.class,f.company(),task.id(),f.today())).isEqualTo("RETAINED");
        var second=ready(f,60,f.second(),null,List.of()); commit(f,simulate(f,second.id())); transition(f,second.id(),TransitionAction.CANCEL);
        assertThat(occupied(f,f.second(),f.today())).isZero();
    }
    @Test void certifiedSkillsAreMandatoryAndSelfReportIsNotEnough() {
        var f=fixture(); UUID skill=UUID.randomUUID();
        jdbc.update("INSERT INTO skill(company_id,id,code,name) VALUES (?,?,'JAVA','Java')",f.company(),skill);
        jdbc.update("INSERT INTO member_skill(company_id,member_id,skill_id,level,self_reported) VALUES (?,?,?,5,true)",f.company(),f.first(),skill);
        jdbc.update("INSERT INTO member_skill(company_id,member_id,skill_id,level,self_reported,certified_by,certified_at) VALUES (?,?,?,3,false,?,now())",f.company(),f.second(),skill,f.first());
        var task=ready(f,60,null,f.first(),List.of(new SkillRequirementDto("JAVA",3,true)));
        var plan=simulate(f,task.id()); assertThat(plan.status()).isEqualTo("READY");
        assertThat(plan.output().fragments()).allSatisfy(x->assertThat(x.memberId()).isEqualTo(f.second()));
        var impossible=ready(f,60,null,null,List.of(new SkillRequirementDto("JAVA",4,true)));
        assertThat(simulate(f,impossible.id()).output().conflicts()).anySatisfy(x->assertThat(x.code()).isEqualTo("NO_SKILLED_MEMBER"));
    }
    @Test void staleAndCrossCompanyPlansCannotCommitAndLockedTasksStayFixed() {
        var f=fixture(); var task=ready(f,60,f.first(),null,List.of()); var plan=simulate(f,task.id());
        engine.setLocked(f.user(),f.company(),task.id(),true,"freeze");
        assertThatThrownBy(()->commit(f,plan)).hasFieldOrPropertyWithValue("code","PLAN_STALE");
        assertThat(simulate(f,task.id()).status()).isEqualTo("INFEASIBLE");
        var other=fixture();
        assertThatThrownBy(()->engine.get(other.user(),other.company(),plan.id())).isInstanceOf(BusinessException.class);
        engine.setLocked(f.user(),f.company(),task.id(),false,"unfreeze");
        commit(f,simulate(f,task.id()));
        engine.setLocked(f.user(),f.company(),task.id(),true,"freeze valid plan");
        var frozen=simulate(f,task.id()); assertThat(frozen.output().fragments()).isEmpty();
        assertThat(frozen.status()).isEqualTo("READY");
    }
    @Test void concurrentCommitHasOneOutcomeAndIdempotentReplay() throws Exception {
        var f=fixture(); var a=ready(f,240,f.first(),null,List.of()); var b=ready(f,240,f.first(),null,List.of());
        var p1=simulate(f,a.id()); var p2=simulate(f,b.id());
        var barrier=new CyclicBarrier(2); var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(p1,p2).stream().map(p->pool.submit(()->{
                barrier.await(); try { commit(f,p); return "ok"; } catch(BusinessException conflict) { return conflict.code(); }
            })).toList();
            var outcomes=new ArrayList<String>(); for(var future:futures) outcomes.add(future.get(30,TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("ok","PLAN_STALE");
            assertThat(occupied(f,f.first(),f.today())).isEqualTo(240);
            var winner=engine.get(f.user(),f.company(),p1.id()).status().equals("COMMITTED")?p1:p2;
            commit(f,winner); assertThat(occupied(f,f.first(),f.today())).isEqualTo(240);
            assertThatThrownBy(()->engine.commit(f.user(),f.company(),winner.id(),winner.version()+1,"commit-"+winner.id()))
                    .hasFieldOrPropertyWithValue("code","IDEMPOTENCY_KEY_REUSED");
        } finally {pool.shutdownNow();}
    }
    @Test void aiDecompositionRequiresHumanConfirmationThenBuildsTasksAndSchedulesDependencies() {
        var f=fixture();
        var estimate=new com.company.scheduler.agent.ai.AiAnalysisOutput.Estimate(60,60,60,"fixed sample");
        var first=new com.company.scheduler.agent.ai.AiAnalysisOutput.Subtask("S1","Design","Design API",List.of("Design"),List.of("Approved"),estimate,List.of());
        var second=new com.company.scheduler.agent.ai.AiAnalysisOutput.Subtask("S2","Implement","Implement API",List.of("Code"),List.of("Test passed"),estimate,List.of());
        var output=new com.company.scheduler.agent.ai.AiAnalysisOutput("1.0","BACKEND","Complete API",true,
                new com.company.scheduler.agent.ai.AiAnalysisOutput.Estimate(120,120,120,"two tasks"),List.of(),List.of("Accepted"),
                List.of(new com.company.scheduler.agent.ai.AiAnalysisOutput.Assumption("Existing database",false)),List.of(),0.9,
                List.of(first,second),List.of(new com.company.scheduler.agent.ai.AiAnalysisOutput.DependencyEdge("S1","S2","FS")));
        org.mockito.Mockito.doReturn(new com.company.scheduler.shared.contract.AiModelGateway.AnalysisResult("1.0",
                com.company.scheduler.agent.AgentJson.toJson(output),"fixture"))
                .when(gateway).analyze(org.mockito.ArgumentMatchers.any());
        var draft=tasks.create(f.user(),f.company(),new CreateTaskRequest(f.project(),null,"Large task","Build API","BACKEND",TaskPriority.P2,
                null,null,f.today(),null,null,null,"Accepted",List.of()),null);
        tasks.submit(f.user(),f.company(),draft.id(),draft.version());
        var run=confirmations.list(f.user(),f.company(),draft.id()).getFirst(); runner.pollOnce();
        assertThat(confirmations.get(f.user(),f.company(),run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(tasks.detail(f.user(),f.company(),draft.id()).children()).isEmpty();
        assertThatThrownBy(()->confirmations.confirm(f.user(),f.company(),run.id(),false))
                .hasFieldOrPropertyWithValue("code","ASSUMPTIONS_UNCONFIRMED");
        var result=confirmations.confirm(f.user(),f.company(),run.id(),true);
        assertThat(result.taskIds()).hasSize(2);
        assertThat(tasks.detail(f.user(),f.company(),draft.id()).children()).hasSize(2);
        assertThat(confirmations.confirm(f.user(),f.company(),run.id(),true)).isEqualTo(result);
        runner.pollOnce(); var plan=engine.byJob(f.user(),f.company(),result.simulationJobId());
        assertThat(plan.status()).isEqualTo("READY"); commit(f,plan);
        var dates=plan.output().fragments().stream().map(x->x.date()).sorted().toList();
        assertThat(dates).containsExactly(f.today(),f.today().plusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_allocation WHERE company_id=? AND task_id=?",Integer.class,f.company(),draft.id())).isZero();
    }

    @Autowired RemainingEstimateService remaining;
    @Test void remainingWorkReplanKeepsTodayAndAssigneeButReplacesFutureMinutes() {
        var f=fixture(); var task=ready(f,480,f.first(),null,List.of()); commit(f,simulate(f,task.id()));
        var started=transition(f,task.id(),TransitionAction.START);
        var revised=remaining.confirm(f.user(),f.company(),task.id(),new RemainingEstimateRequest(60,f.today()),started.version());
        assertThat(revised.scheduleStatus()).isEqualTo(ScheduleStatus.STALE);
        var plan=simulate(f,task.id()); assertThat(plan.status()).isEqualTo("READY");
        assertThat(plan.output().fragments()).singleElement().satisfies(x->{
            assertThat(x.memberId()).isEqualTo(f.first()); assertThat(x.date()).isEqualTo(f.today().plusDays(1)); assertThat(x.minutes()).isEqualTo(60);
        });
        commit(f,plan);
        assertThat(occupied(f,f.first(),f.today())).isEqualTo(360);
        assertThat(occupied(f,f.first(),f.today().plusDays(1))).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT execution_started FROM task_allocation WHERE company_id=? AND task_id=? AND work_date=? AND occupancy_state='RESERVED'",
                Boolean.class,f.company(),task.id(),f.today())).isTrue();
    }
    @Test void parentHardDeadlineIsInheritedAndInfeasiblePlanCannotReserve() {
        var f=fixture();
        var parent=tasks.create(f.user(),f.company(),new CreateTaskRequest(f.project(),null,"Deadline parent","scope","BACKEND",TaskPriority.P2,
                f.today(),DueType.HARD,f.today(),null,null,null,"Accepted",List.of()),null);
        var child=tasks.create(f.user(),f.company(),new CreateTaskRequest(f.project(),parent.id(),"Large child","scope","BACKEND",TaskPriority.P2,
                null,null,f.today(),480,f.first(),null,"Accepted",List.of()),null);
        var submitted=tasks.submit(f.user(),f.company(),child.id(),child.version());
        transitions.transition(f.user(),f.company(),child.id(),new TransitionRequest(TransitionAction.MARK_READY,null,null,null),submitted.version());
        var plan=simulate(f,parent.id());
        assertThat(plan.status()).isEqualTo("INFEASIBLE");
        assertThat(plan.output().tasks()).singleElement().satisfies(t->assertThat(t.hardDeadline()).isEqualTo(f.today()));
        assertThat(plan.output().conflicts()).isNotEmpty();
        assertThatThrownBy(()->commit(f,plan)).hasFieldOrPropertyWithValue("code","PLAN_NOT_READY");
        assertThat(occupied(f,f.first(),f.today())).isZero();
    }
    @Test void editedSubmittedTaskCanBeReanalyzedWithANewVersion() {
        var f=fixture();
        var draft=tasks.create(f.user(),f.company(),new CreateTaskRequest(f.project(),null,"Needs information","initial","BACKEND",TaskPriority.P2,
                null,null,f.today(),null,null,null,"Accepted",List.of()),null);
        var submitted=tasks.submit(f.user(),f.company(),draft.id(),draft.version());
        var first=confirmations.list(f.user(),f.company(),draft.id()).getFirst();
        tasks.update(f.user(),f.company(),draft.id(),new UpdateTaskRequest(submitted.title(),"updated requirements","BACKEND",TaskPriority.P2,
                null,null,f.today(),null,null,null,"Accepted",List.of()),submitted.version());
        var second=confirmations.analyze(f.user(),f.company(),draft.id());
        assertThat(second.runId()).isNotEqualTo(first.id());
        assertThat(confirmations.analyze(f.user(),f.company(),draft.id())).isEqualTo(second);
        assertThat(confirmations.list(f.user(),f.company(),draft.id())).hasSize(2);
    }

}
