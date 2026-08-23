package com.javaee.user.dto;

import lombok.Data;

/**
 * @author wy
 * @description: 用户注册DTO
 */
@Data
public class UserRegisterDTO {

    private String username;

    private String password;

    private String email;

    private String phone;
}
