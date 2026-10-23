package com.quanlybanhang.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.time.*;

@Configuration
public class DashboardConfig {
    @Bean("dashboardClock")
    public Clock dashboardClock(@Value("${app.dashboard.zone:Asia/Ho_Chi_Minh}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}