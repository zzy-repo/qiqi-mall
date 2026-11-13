package com.qiqi.mall.audit.schedule;

import com.qiqi.mall.audit.entity.AuditRun;
import com.qiqi.mall.audit.service.AuditExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 定时自动审核：每分钟醒一次，按 {@code order_setting.audit_run_interval_min} 决定要不要真跑。
 *
 * <h3>为什么是「每分钟醒 + 自己判断间隔」而不是 {@code @Scheduled(fixedRate = 间隔)}</h3>
 * 间隔是**库里的配置**（运营随时能改），注解里的数字是编译期常量 —— 写死就得重启才生效。
 * 醒来的成本接近零（一次 {@code SELECT}），判断留给运行期。
 *
 * <h3>幂等</h3>
 * 每次真跑都带 {@code idem_key = SCHEDULE:yyyyMMddHHmm}：多实例同时醒、或同一分钟被重复投递，
 * 只有第一轮会建 run 行，其余直接返回既有那一轮（见 {@code AuditExecutor#run}）。
 */
@Component
public class AuditScheduleJob {

    private static final Logger log = LoggerFactory.getLogger(AuditScheduleJob.class);

    private final AuditExecutor executor;

    /**
     * 调度开关（{@code qiqi.audit.schedule-enabled}，默认 true）。
     *
     * <p>为什么要这个开关：自动审核会真的把订单推到 8（不可逆的业务动作）。
     * 出故障时运维的第一反应应该是「先停调度」，而不是「去库里改开关等重启」；
     * 集成测试也可以在自己的上下文里关掉它，免得后台线程在断言之间偷偷跑一轮。
     */
    private final boolean scheduleEnabled;

    public AuditScheduleJob(AuditExecutor executor,
                            @Value("${qiqi.audit.schedule-enabled:true}") boolean scheduleEnabled) {
        this.executor = executor;
        this.scheduleEnabled = scheduleEnabled;
    }

    /** initialDelay 5 分钟：启动瞬间不抢跑（应用刚起来时库存缓存/策略还没预热完） */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 300_000L)
    public void tick() {
        if (!scheduleEnabled) {
            return;
        }
        try {
            if (!executor.autoAuditEnabled()) {
                log.debug("自动审核总开关关闭，跳过本轮调度");
                return;
            }
            int interval = executor.intervalMinutes();
            AuditRun latest = executor.latestRun();
            LocalDateTime now = LocalDateTime.now();
            if (latest != null && latest.getCreatedAt() != null
                    && Duration.between(latest.getCreatedAt(), now).toMinutes() < interval) {
                return;
            }
            executor.run("SCHEDULE", AuditExecutor.scheduleIdemKey(now));
        } catch (Exception e) {
            // 调度线程里绝不能把异常抛出去：@Scheduled 方法抛异常会让后续调度按策略停滞
            log.error("定时自动审核失败（下一分钟重试）: {}", e.getMessage(), e);
        }
    }
}
