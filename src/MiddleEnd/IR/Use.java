package MiddleEnd.IR;

import MiddleEnd.IR.Value.User;
import MiddleEnd.IR.Value.Value;

public class Use {
    private Value value;
    private User user;
    
    public Use(Value value, User user) {
        this.value = value;
        this.user = user;
    }
    
    public Value getValue() {
        return value;
    }
    
    public void setValue(Value value) {
        this.value = value;
    }
    
    public User getUser() {
        return user;
    }
} 