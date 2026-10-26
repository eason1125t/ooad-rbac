/** 功能：核验业务请求的ID/分页溢出、字面检索与并发版本边界；补充真实HTTP场景无法到达的空值边界。 */
package com.municipal.rbac.shared;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BusinessInputPolicyTest {
    @Test
    void idsRejectMalformedAndOverflowWithoutTruncating() {
        assertThat(BusinessData.id("1")).isEqualTo(1);
        assertThat(BusinessData.id(Long.toString(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
        for (String value :
                new String[] {
                    null, "", "0", "-1", " 1", "1.5", "9223372036854775808", "99999999999999999999"
                })
            assertThatThrownBy(() -> BusinessData.id(value))
                    .isInstanceOfSatisfying(
                            DomainFailure.class,
                            e -> {
                                assertThat(e.status()).isEqualTo(400);
                                assertThat(e.code()).isEqualTo("INVALID_ID");
                            });
    }

    @Test
    void pageLimitsAndIntegerOverflowAreRejectedBeforeSql() {
        assertThat(BusinessData.offset(1, 100)).isZero();
        assertThat(BusinessData.offset(3, 20)).isEqualTo(40);
        for (int[] input : new int[][] {{0, 20}, {1, 0}, {1, 101}, {Integer.MAX_VALUE, 100}})
            assertThatThrownBy(() -> BusinessData.offset(input[0], input[1]))
                    .isInstanceOfSatisfying(
                            DomainFailure.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void searchTreatsWildcardsAsLiteralAndBoundsInput() {
        assertThat(BusinessData.search(null)).isEmpty();
        assertThat(BusinessData.search("  100%_!  ")).isEqualTo("100!%!_!!");
        assertThat(BusinessData.search("x".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> BusinessData.search("x".repeat(101)))
                .isInstanceOfSatisfying(
                        DomainFailure.class, e -> assertThat(e.code()).isEqualTo("INVALID_SEARCH"));
    }

    @Test
    void missingNegativeAndStaleVersionsCannotOverwrite() {
        BusinessData.version(0, 0L);
        for (Long value : new Long[] {null, -1L})
            assertThatThrownBy(() -> BusinessData.version(0, value))
                    .isInstanceOfSatisfying(
                            DomainFailure.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> BusinessData.version(2, 1L))
                .isInstanceOfSatisfying(
                        DomainFailure.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(409);
                            assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                        });
    }
}
