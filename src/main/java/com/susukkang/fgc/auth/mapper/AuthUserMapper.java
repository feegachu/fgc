package com.susukkang.fgc.auth.mapper;

import com.susukkang.fgc.auth.dto.AppUserView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// FUN-001 개발 순서 4

@Mapper
public interface AuthUserMapper {

    /** 로그인 아이디로 사용자와 역할코드를 조회한다. 없으면 null */
    AppUserView findByLoginId(@Param("loginId") String loginId);
}
