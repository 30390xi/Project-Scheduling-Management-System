package com.company.scheduler.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 登录限流配置：单账号连续失败锁定 + 单来源 IP 失败窗口限流。 */
@ConfigurationProperties(prefix = "scheduler.auth.login")
public class LoginSecurityProperties {
    /** 单账号连续失败达到该次数后锁定。 */
    private int maxFailedAttempts = 5;
    /** 账号锁定时长（分钟）。 */
    private int lockMinutes = 10;
    /** 单 IP 在窗口内允许的最大失败次数。 */
    private int ipMaxFailedAttempts = 30;
    /** IP 窗口长度（分钟）。 */
    private int ipWindowMinutes = 10;

    public int getMaxFailedAttempts() { return maxFailedAttempts; }
    public void setMaxFailedAttempts(int maxFailedAttempts) { this.maxFailedAttempts = maxFailedAttempts; }
    public int getLockMinutes() { return lockMinutes; }
    public void setLockMinutes(int lockMinutes) { this.lockMinutes = lockMinutes; }
    public int getIpMaxFailedAttempts() { return ipMaxFailedAttempts; }
    public void setIpMaxFailedAttempts(int ipMaxFailedAttempts) { this.ipMaxFailedAttempts = ipMaxFailedAttempts; }
    public int getIpWindowMinutes() { return ipWindowMinutes; }
    public void setIpWindowMinutes(int ipWindowMinutes) { this.ipWindowMinutes = ipWindowMinutes; }
}
