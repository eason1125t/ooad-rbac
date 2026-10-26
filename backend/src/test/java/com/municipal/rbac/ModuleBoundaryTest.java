/** 功能：约束有实际风险的模块依赖；业务不得读取授权内部，权限核心不得耦合HTTP/业务模块。 */
package com.municipal.rbac;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
    @Test
    void keepsBusinessAndAuthorizationBoundaries() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.municipal.rbac");
        noClasses()
                .that()
                .resideInAnyPackage("..oa..", "..pmis..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..authorization.internal..", "..identity..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..authorization..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..oa..",
                        "..pmis..",
                        "..security..",
                        "jakarta.servlet..",
                        "org.springframework.web..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..identity..")
                .should()
                .dependOnClassesThat()
                // 禁止项目/框架安全适配依赖，允许JDK的SecureRandom生成正式临时口令。
                .resideInAnyPackage(
                        "com.municipal.rbac.authorization..",
                        "com.municipal.rbac.oa..",
                        "com.municipal.rbac.pmis..",
                        "com.municipal.rbac.security..",
                        "org.springframework.security..",
                        "org.springframework.web..",
                        "jakarta.servlet..")
                .check(classes);
    }
}
