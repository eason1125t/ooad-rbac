/** 功能：可控业务拒绝；由唯一MVC异常出口转换为状态码/安全错误，不携带原始请求或秘密。 */
package com.municipal.rbac.shared;

public class DomainFailure extends RuntimeException {
    private final int status;
    private final String code;

    public DomainFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
