package MiddleEnd.IR.Value;

import MiddleEnd.IR.Type.Type;
import java.util.ArrayList;
import java.util.List;

public class Value {
    private String name;
    private Type type;
    private List<User> users = new ArrayList<>();
    
    public Value(String name, Type type) {
        this.name = name;
        this.type = type;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public Type getType() {
        return type;
    }
    
    public void addUser(User user) {
        if (!users.contains(user)) {
            users.add(user);
        }
    }
    
    public void removeUser(User user) {
        users.remove(user);
    }
    
    public List<User> getUsers() {
        return users;
    }
    
    public boolean isUsed() {
        return !users.isEmpty();
    }
    
    @Override
    public String toString() {
        return name + ":" + type;
    }
} 