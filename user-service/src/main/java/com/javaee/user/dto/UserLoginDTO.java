package com.javaee.user.dto;

import lombok.Data;

/**
 * @author wy
 * @description: 用户登录DTO
 */
@Data
public class UserLoginDTO {

    private String username;

    private String password;
}
