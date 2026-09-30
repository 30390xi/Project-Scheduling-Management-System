package com.company.scheduler.identity.service;

import java.security.SecureRandom;

/** 一次性临时密码：只在创建/重置响应中返回一次，首次登录强制修改，不落日志。 */
final class TemporaryPassword {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int LENGTH = 20;

    private TemporaryPassword() {}

    static String generate() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
