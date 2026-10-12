package com.fuyouxuan.mapper;

import com.fuyouxuan.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @since 2021-12-24
 */
public interface UserInfoMapper extends BaseMapper<UserInfo> {

    @Insert("INSERT IGNORE INTO tb_user_info (user_id, credits) VALUES (#{userId}, 500)")
    int insertDefault(@Param("userId") Long userId);

    @Update("UPDATE tb_user_info SET credits = credits - #{amount} " +
            "WHERE user_id = #{userId} AND credits >= #{amount}")
    int deductCredits(@Param("userId") Long userId, @Param("amount") Integer amount);
}
