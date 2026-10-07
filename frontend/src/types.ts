export interface Company {
    id: string;
    name: string;
    role: string;
    timezone: string;
}
export interface Project {
    id: string;
    name: string;
    version?: number;
    autoAssignEnabled?: boolean;
}
export interface Member {
    memberId: string;
    userId: string;
    username: string;
    role: string;
    status: string;
    version: number;
}
export interface ProjectMember {
    memberId: string;
    username: string;
    role: string;
    resourcePermission: boolean;
}
export interface Skill {
    skillId: string;
    code: string;
    name: string;
    active: boolean;
    description?: string;
}
export interface MemberSkill extends Skill {
    level: number;
    selfReported: boolean;
}
export interface Requirement {
    skillCode: string;
    minLevel: number;
    required: boolean;
}
export interface Task {
    id: string;
    taskNo: number | null;
    projectId: string;
    parentId: string | null;
    nodeType: string;
    title: string;
    status: string;
    scheduleStatus: string;
    priority: string;
    taskType: string;
    assigneeId: string | null;
    estimatedMinutes: number | null;
    actualMinutes: number;
    remainingMinutes: number | null;
    dueDate: string | null;
    dueType: string;
    version: number;
    updatedAt: string;
}
export interface TaskDetail extends Task {
    description: string;
    acceptanceCriteria: string;
    earliestStartDate: string | null;
    preferredMemberId: string | null;
    fixedAssigneeId: string | null;
    skills: Requirement[];
    children: Task[];
    predecessors: { taskId: string; title: string; status: string }[];
    events: {
        fromStatus: string;
        toStatus: string;
        reason: string;
        createdAt: string;
    }[];
    workLogs: { workDate: string; minutes: number; note: string }[];
    blockedReason: string | null;
    cancelReason: string | null;
}
export interface TaskInput {
    projectId?: string;
    title: string;
    description: string;
    taskType: string;
    priority: string;
    estimatedMinutes: number | null;
    dueDate: string | null;
    dueType: string;
    earliestStartDate: string | null;
    fixedAssigneeId: string | null;
    preferredMemberId: string | null;
    acceptanceCriteria: string;
    skills: Requirement[];
}
export interface TaskPage {
    items: Task[];
    nextCursor: string | null;
}
export interface Estimate {
    optimisticMinutes: number;
    likelyMinutes: number;
    pessimisticMinutes: number;
    basis: string;
}
export interface AnalysisOutput {
    summary: string;
    taskType: string;
    estimate: Estimate;
    requiredSkills: { code: string; minLevel: number }[];
    acceptanceCriteria: string[];
    assumptions: { text: string; confirmed: boolean }[];
    missingInformation: string[];
    subtasks: {
        clientKey: string;
        title: string;
        scope: string;
        estimate: Estimate;
        acceptanceCriteria: string[];
        deliverables: string[];
    }[];
    dependencies: { from: string; to: string; type: string }[];
}
export interface Analysis {
    id: string;
    status: string;
    model: string;
    validatedOutputJson: string | null;
    errorCode: string | null;
    errorDetail: string | null;
    qualityScore: number | null;
    createdAt: string;
    providerCalls: number;
}
export interface Fragment {
    taskId: string;
    memberId: string;
    date: string;
    minutes: number;
}
export interface Allocation extends Fragment {
    occupancyState: string;
    executionStarted: boolean;
}
export interface Capacity {
    memberId: string;
    workDate: string;
    workday: boolean;
    capacityMinutes: number;
    occupiedMinutes: number;
    remainingMinutes: number;
    standardMinutes: number;
    dailyLimitMinutes: number;
    leaveMinutes: number;
}
export interface Plan {
    id: string;
    jobId: string;
    version: number;
    status: string;
    expiresAt: string;
    output: {
        tasks: { id: string; title: string }[];
        fragments: Fragment[];
        conflicts: { taskId: string; code: string; message: string }[];
    } | null;
}
