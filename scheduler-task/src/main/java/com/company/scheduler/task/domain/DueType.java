package com.company.scheduler.task.domain;

/** 截止类型：HARD 硬截止、SOFT 期望截止（默认）。AI 不得把 SOFT 升级为 HARD。 */
public enum DueType { HARD, SOFT }
