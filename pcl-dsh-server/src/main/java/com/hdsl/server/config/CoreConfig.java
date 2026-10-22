package com.hdsl.server.config;

import com.hdsl.account.AccountService;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 复用 pcl-dsh-core 业务层的 Spring Bean 装配。 */
@Configuration
public class CoreConfig {

    /**
     * 实例清单是唯一写入方：文件锁已存在时说明另一个 PCL-DSH 进程正在使用同一数据目录，
     * 启动必须失败并给出中文可操作提示，避免两个进程同时改写 launcher-v2.json。
     */
    @Bean(destroyMethod = "close")
    public InstanceStore instanceStore(DataRoot data) {
        try {
            return new InstanceStore(data.root());
        } catch (IOException e) {
            throw new IllegalStateException("无法打开实例清单（数据目录可能正被另一个 PCL-DSH 进程占用）：" + data.root()
                    + "。请先停止另一个进程；如果确认没有其他进程，请检查 " + data.config().resolve("launcher.lock")
                    + " 的占用情况，或通过 PCL_DSH_DATA / -Dpcl.data 使用不同的数据目录。原因：" + e.getMessage(), e);
        }
    }

    @Bean(destroyMethod = "close")
    public ProcessService processService() {
        return new ProcessService();
    }

    @Bean
    public AccountService accountService(DataRoot data) {
        return new AccountService(data.root());
    }
}
