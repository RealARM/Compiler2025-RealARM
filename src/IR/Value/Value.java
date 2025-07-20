package IR.Value;

import IR.Type.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * IR值的基类，所有IR值都继承自此类
 */
public class Value {
    private String name;       // 值的名称
    private Type type;         // 值的类型
    private List<User> users = new ArrayList<>();  // 使用该值的用户列表
    
    public Value(String name, Type type) {
        this.name = name;
        this.type = type;
    }
    
    /**
     * 获取值的名称
     */
    public String getName() {
        return name;
    }
    
    /**
     * 设置值的名称
     */
    public void setName(String name) {
        this.name = name;
    }
    
    /**
     * 获取值的类型
     */
    public Type getType() {
        return type;
    }
    
    /**
     * 添加使用此值的用户
     */
    public void addUser(User user) {
        if (!users.contains(user)) {
            users.add(user);
        }
    }
    
    /**
     * 移除使用此值的用户
     */
    public void removeUser(User user) {
        users.remove(user);
    }
    
    /**
     * 获取使用此值的所有用户
     */
    public List<User> getUsers() {
        return users;
    }
    
    /**
     * 判断值是否被使用
     */
    public boolean isUsed() {
        return !users.isEmpty();
    }
    
    /**
     * 获取值的字符串表示
     */
    @Override
    public String toString() {
        return name + ":" + type;
    }
} 