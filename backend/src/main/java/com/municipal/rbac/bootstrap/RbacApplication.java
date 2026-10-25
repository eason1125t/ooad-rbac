/** 功能：模块化单体启动；扫描功能组件与显式MyBatis Mapper，配置连接见application.yml。 */
package com.municipal.rbac.bootstrap;

import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.municipal.rbac")
@MapperScan(basePackages = "com.municipal.rbac", annotationClass = Mapper.class)
public class RbacApplication {
    public static void main(String[] args) {
        SpringApplication.run(RbacApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
