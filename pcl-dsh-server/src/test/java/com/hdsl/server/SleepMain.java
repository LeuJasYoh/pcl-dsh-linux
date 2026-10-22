package com.hdsl.server;

/**
 * 测试夹具：一个长期运行的普通 Java 进程，用于在不依赖 Node/DSH 的情况下
 * 模拟“实例进程正在运行”，验证运行中实例的 409、停止与单实例限制。
 */
public final class SleepMain {

    private SleepMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(120_000L);
    }
}
