package MiddleEnd.IR;

import MiddleEnd.IR.Value.User;
import MiddleEnd.IR.Value.Value;

/**
 * 表示一个Value被一个User使用的关系
 */
public class Use {
    private Value value;  // 被使用的值
    private User user;    // 使用该值的用户
    
    public Use(Value value, User user) {
        this.value = value;
        this.user = user;
    }
    
    /**
     * 获取被使用的值
     */
    public Value getValue() {
        return value;
    }
    
    /**
     * 设置被使用的值
     */
    public void setValue(Value value) {
        this.value = value;
    }
    
    /**
     * 获取使用该值的用户
     */
    public User getUser() {
        return user;
    }
} 