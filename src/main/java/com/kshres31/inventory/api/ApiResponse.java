package com.kshres31.inventory.api;

/** A status code and an optional body that will be serialized as JSON. */
public record ApiResponse(int status, Object body) {
    public static ApiResponse ok(Object body) {
        return new ApiResponse(200, body);
    }

    public static ApiResponse created(Object body) {
        return new ApiResponse(201, body);
    }

    public static ApiResponse noContent() {
        return new ApiResponse(204, null);
    }
}
