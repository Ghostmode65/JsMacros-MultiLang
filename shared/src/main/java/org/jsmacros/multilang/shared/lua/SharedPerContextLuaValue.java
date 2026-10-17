package org.jsmacros.multilang.shared.lua;

import org.luaj.vm2.*;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

public class SharedPerContextLuaValue extends LuaValue {
    private final Supplier<?> activeContext;
    private final Map<Object, LuaValue> instances = Collections.synchronizedMap(new WeakHashMap<>());

    public SharedPerContextLuaValue(Supplier<?> activeContext) {
        super();
        this.activeContext = activeContext;
    }

    public void addContext(Object ctx, LuaValue javaInstance) {
        instances.put(ctx, javaInstance);
    }

    private LuaValue currentValue() {
        LuaValue value = instances.get(activeContext.get());
        return value == null ? LuaValue.NIL : value;
    }

    @Override
    public boolean isboolean() {
        return currentValue().isboolean();
    }

    @Override
    public boolean isclosure() {
        return currentValue().isclosure();
    }

    @Override
    public boolean isfunction() {
        return currentValue().isfunction();
    }

    @Override
    public boolean isint() {
        return currentValue().isint();
    }

    @Override
    public boolean isinttype() {
        return currentValue().isinttype();
    }

    @Override
    public boolean islong() {
        return currentValue().islong();
    }

    @Override
    public boolean isnil() {
        return currentValue().isnil();
    }

    @Override
    public boolean isnumber() {
        return currentValue().isnumber();
    }

    @Override
    public boolean isstring() {
        return currentValue().isstring();
    }

    @Override
    public boolean isthread() {
        return currentValue().isthread();
    }

    @Override
    public boolean istable() {
        return currentValue().istable();
    }

    @Override
    public boolean isuserdata() {
        return currentValue().isuserdata();
    }

    @Override
    public boolean isuserdata(Class c) {
        return currentValue().isuserdata(c);
    }

    @Override
    public boolean toboolean() {
        return currentValue().toboolean();
    }

    @Override
    public byte tobyte() {
        return currentValue().tobyte();
    }

    @Override
    public char tochar() {
        return currentValue().tochar();
    }

    @Override
    public double todouble() {
        return currentValue().todouble();
    }

    @Override
    public float tofloat() {
        return currentValue().tofloat();
    }

    @Override
    public int toint() {
        return currentValue().toint();
    }

    @Override
    public long tolong() {
        return currentValue().tolong();
    }

    @Override
    public short toshort() {
        return currentValue().toshort();
    }

    @Override
    public String tojstring() {
        return currentValue().tojstring();
    }

    @Override
    public Object touserdata() {
        return currentValue().touserdata();
    }

    @Override
    public Object touserdata(Class c) {
        return currentValue().touserdata(c);
    }

    @Override
    public String toString() {
        return currentValue().toString();
    }

    @Override
    public LuaValue tonumber() {
        return currentValue().tonumber();
    }

    @Override
    public LuaValue tostring() {
        return currentValue().tostring();
    }

    @Override
    public boolean optboolean(boolean defval) {
        return currentValue().optboolean(defval);
    }

    @Override
    public LuaClosure optclosure(LuaClosure defval) {
        return currentValue().optclosure(defval);
    }

    @Override
    public double optdouble(double defval) {
        return currentValue().optdouble(defval);
    }

    @Override
    public LuaFunction optfunction(LuaFunction defval) {
        return currentValue().optfunction(defval);
    }

    @Override
    public int optint(int defval) {
        return currentValue().optint(defval);
    }

    @Override
    public LuaInteger optinteger(LuaInteger defval) {
        return currentValue().optinteger(defval);
    }

    @Override
    public long optlong(long defval) {
        return currentValue().optlong(defval);
    }

    @Override
    public LuaNumber optnumber(LuaNumber defval) {
        return currentValue().optnumber(defval);
    }

    @Override
    public String optjstring(String defval) {
        return currentValue().optjstring(defval);
    }

    @Override
    public LuaString optstring(LuaString defval) {
        return currentValue().optstring(defval);
    }

    @Override
    public LuaTable opttable(LuaTable defval) {
        return currentValue().opttable(defval);
    }

    @Override
    public LuaThread optthread(LuaThread defval) {
        return currentValue().optthread(defval);
    }

    @Override
    public Object optuserdata(Object defval) {
        return currentValue().optuserdata(defval);
    }

    @Override
    public Object optuserdata(Class c, Object defval) {
        return currentValue().optuserdata(c, defval);
    }

    @Override
    public LuaValue optvalue(LuaValue defval) {
        return currentValue().optvalue(defval);
    }

    @Override
    public boolean checkboolean() {
        return currentValue().checkboolean();
    }

    @Override
    public LuaClosure checkclosure() {
        return currentValue().checkclosure();
    }

    @Override
    public double checkdouble() {
        return currentValue().checkdouble();
    }

    @Override
    public LuaFunction checkfunction() {
        return currentValue().checkfunction();
    }

    @Override
    public Globals checkglobals() {
        return currentValue().checkglobals();
    }

    @Override
    public int checkint() {
        return currentValue().checkint();
    }

    @Override
    public LuaInteger checkinteger() {
        return currentValue().checkinteger();
    }

    @Override
    public long checklong() {
        return currentValue().checklong();
    }

    @Override
    public LuaNumber checknumber() {
        return currentValue().checknumber();
    }

    @Override
    public LuaNumber checknumber(String msg) {
        return currentValue().checknumber(msg);
    }

    @Override
    public String checkjstring() {
        return currentValue().checkjstring();
    }

    @Override
    public LuaString checkstring() {
        return currentValue().checkstring();
    }

    @Override
    public LuaTable checktable() {
        return currentValue().checktable();
    }

    @Override
    public LuaThread checkthread() {
        return currentValue().checkthread();
    }

    @Override
    public Object checkuserdata() {
        return currentValue().checkuserdata();
    }

    @Override
    public Object checkuserdata(Class c) {
        return currentValue().checkuserdata(c);
    }

    @Override
    public LuaValue checknotnil() {
        return currentValue().checknotnil();
    }

    @Override
    public boolean isvalidkey() {
        return currentValue().isvalidkey();
    }

    @Override
    public LuaValue get(LuaValue key) {
        return currentValue().get(key);
    }

    @Override
    public LuaValue get(int key) {
        return currentValue().get(key);
    }

    @Override
    public LuaValue get(String key) {
        return currentValue().get(key);
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        currentValue().set(key, value);
    }

    @Override
    public void set(int key, LuaValue value) {
        currentValue().set(key, value);
    }

    @Override
    public void set(int key, String value) {
        currentValue().set(key, value);
    }

    @Override
    public void set(String key, LuaValue value) {
        currentValue().set(key, value);
    }

    @Override
    public void set(String key, double value) {
        currentValue().set(key, value);
    }

    @Override
    public void set(String key, int value) {
        currentValue().set(key, value);
    }

    @Override
    public void set(String key, String value) {
        currentValue().set(key, value);
    }

    @Override
    public LuaValue rawget(LuaValue key) {
        return currentValue().rawget(key);
    }

    @Override
    public LuaValue rawget(int key) {
        return currentValue().rawget(key);
    }

    @Override
    public LuaValue rawget(String key) {
        return currentValue().rawget(key);
    }

    @Override
    public void rawset(LuaValue key, LuaValue value) {
        currentValue().rawset(key, value);
    }

    @Override
    public void rawset(int key, LuaValue value) {
        currentValue().rawset(key, value);
    }

    @Override
    public void rawset(int key, String value) {
        currentValue().rawset(key, value);
    }

    @Override
    public void rawset(String key, LuaValue value) {
        currentValue().rawset(key, value);
    }

    @Override
    public void rawset(String key, double value) {
        currentValue().rawset(key, value);
    }

    @Override
    public void rawset(String key, int value) {
        currentValue().rawset(key, value);
    }

    @Override
    public void rawset(String key, String value) {
        currentValue().rawset(key, value);
    }

    @Override
    public void rawsetlist(int key0, Varargs values) {
        currentValue().rawsetlist(key0, values);
    }

    @Override
    public void presize(int i) {
        currentValue().presize(i);
    }

    @Override
    public Varargs next(LuaValue index) {
        return currentValue().next(index);
    }

    @Override
    public Varargs inext(LuaValue index) {
        return currentValue().inext(index);
    }

    @Override
    public LuaValue load(LuaValue library) {
        return currentValue().load(library);
    }

    @Override
    public LuaValue arg(int index) {
        return currentValue().arg(index);
    }

    @Override
    public int narg() {
        return currentValue().narg();
    }

    @Override
    public LuaValue arg1() {
        return currentValue().arg1();
    }

    @Override
    public LuaValue getmetatable() {
        return currentValue().getmetatable();
    }

    @Override
    public LuaValue setmetatable(LuaValue metatable) {
        return currentValue().setmetatable(metatable);
    }

    @Override
    public LuaValue call() {
        return currentValue().call();
    }

    @Override
    public LuaValue call(LuaValue arg) {
        return currentValue().call(arg);
    }

    @Override
    public LuaValue call(String arg) {
        return currentValue().call(arg);
    }

    @Override
    public LuaValue call(LuaValue arg1, LuaValue arg2) {
        return currentValue().call(arg1, arg2);
    }

    @Override
    public LuaValue call(LuaValue arg1, LuaValue arg2, LuaValue arg3) {
        return currentValue().call(arg1, arg2, arg3);
    }

    @Override
    public LuaValue method(String name) {
        return currentValue().method(name);
    }

    @Override
    public LuaValue method(LuaValue name) {
        return currentValue().method(name);
    }

    @Override
    public LuaValue method(String name, LuaValue arg) {
        return currentValue().method(name, arg);
    }

    @Override
    public LuaValue method(LuaValue name, LuaValue arg) {
        return currentValue().method(name, arg);
    }

    @Override
    public LuaValue method(String name, LuaValue arg1, LuaValue arg2) {
        return currentValue().method(name, arg1, arg2);
    }

    @Override
    public LuaValue method(LuaValue name, LuaValue arg1, LuaValue arg2) {
        return currentValue().method(name, arg1, arg2);
    }

    @Override
    public Varargs invoke() {
        return currentValue().invoke();
    }

    @Override
    public Varargs invoke(Varargs args) {
        return currentValue().invoke(args);
    }

    @Override
    public Varargs invoke(LuaValue arg, Varargs varargs) {
        return currentValue().invoke(arg, varargs);
    }

    @Override
    public Varargs invoke(LuaValue arg1, LuaValue arg2, Varargs varargs) {
        return currentValue().invoke(arg1, arg2, varargs);
    }

    @Override
    public Varargs invoke(LuaValue[] args) {
        return currentValue().invoke(args);
    }

    @Override
    public Varargs invoke(LuaValue[] args, Varargs varargs) {
        return currentValue().invoke(args, varargs);
    }

    @Override
    public Varargs invokemethod(String name) {
        return currentValue().invokemethod(name);
    }

    @Override
    public Varargs invokemethod(LuaValue name) {
        return currentValue().invokemethod(name);
    }

    @Override
    public Varargs invokemethod(String name, Varargs args) {
        return currentValue().invokemethod(name, args);
    }

    @Override
    public Varargs invokemethod(LuaValue name, Varargs args) {
        return currentValue().invokemethod(name, args);
    }

    @Override
    public Varargs invokemethod(String name, LuaValue[] args) {
        return currentValue().invokemethod(name, args);
    }

    @Override
    public Varargs invokemethod(LuaValue name, LuaValue[] args) {
        return currentValue().invokemethod(name, args);
    }

    @Override
    public LuaValue not() {
        return currentValue().not();
    }

    @Override
    public LuaValue neg() {
        return currentValue().neg();
    }

    @Override
    public LuaValue len() {
        return currentValue().len();
    }

    @Override
    public int length() {
        return currentValue().length();
    }

    @Override
    public int rawlen() {
        return currentValue().rawlen();
    }

    @Override
    public boolean equals(Object obj) {
        return currentValue().equals(obj);
    }

    @Override
    public LuaValue eq(LuaValue val) {
        return currentValue().eq(val);
    }

    @Override
    public boolean eq_b(LuaValue val) {
        return currentValue().eq_b(val);
    }

    @Override
    public LuaValue neq(LuaValue val) {
        return currentValue().neq(val);
    }

    @Override
    public boolean neq_b(LuaValue val) {
        return currentValue().neq_b(val);
    }

    @Override
    public boolean raweq(LuaValue val) {
        return currentValue().raweq(val);
    }

    @Override
    public boolean raweq(LuaUserdata val) {
        return currentValue().raweq(val);
    }

    @Override
    public boolean raweq(LuaString val) {
        return currentValue().raweq(val);
    }

    @Override
    public boolean raweq(double val) {
        return currentValue().raweq(val);
    }

    @Override
    public boolean raweq(int val) {
        return currentValue().raweq(val);
    }

    @Override
    public LuaValue add(LuaValue rhs) {
        return currentValue().add(rhs);
    }

    @Override
    public LuaValue add(double rhs) {
        return currentValue().add(rhs);
    }

    @Override
    public LuaValue add(int rhs) {
        return currentValue().add(rhs);
    }

    @Override
    public LuaValue sub(LuaValue rhs) {
        return currentValue().sub(rhs);
    }

    @Override
    public LuaValue sub(double rhs) {
        return currentValue().sub(rhs);
    }

    @Override
    public LuaValue sub(int rhs) {
        return currentValue().sub(rhs);
    }

    @Override
    public LuaValue subFrom(double lhs) {
        return currentValue().subFrom(lhs);
    }

    @Override
    public LuaValue subFrom(int lhs) {
        return currentValue().subFrom(lhs);
    }

    @Override
    public LuaValue mul(LuaValue rhs) {
        return currentValue().mul(rhs);
    }

    @Override
    public LuaValue mul(double rhs) {
        return currentValue().mul(rhs);
    }

    @Override
    public LuaValue mul(int rhs) {
        return currentValue().mul(rhs);
    }

    @Override
    public LuaValue pow(LuaValue rhs) {
        return currentValue().pow(rhs);
    }

    @Override
    public LuaValue pow(double rhs) {
        return currentValue().pow(rhs);
    }

    @Override
    public LuaValue pow(int rhs) {
        return currentValue().pow(rhs);
    }

    @Override
    public LuaValue powWith(double lhs) {
        return currentValue().powWith(lhs);
    }

    @Override
    public LuaValue powWith(int lhs) {
        return currentValue().powWith(lhs);
    }

    @Override
    public LuaValue div(LuaValue rhs) {
        return currentValue().div(rhs);
    }

    @Override
    public LuaValue div(double rhs) {
        return currentValue().div(rhs);
    }

    @Override
    public LuaValue div(int rhs) {
        return currentValue().div(rhs);
    }

    @Override
    public LuaValue divInto(double lhs) {
        return currentValue().divInto(lhs);
    }

    @Override
    public LuaValue mod(LuaValue rhs) {
        return currentValue().mod(rhs);
    }

    @Override
    public LuaValue mod(double rhs) {
        return currentValue().mod(rhs);
    }

    @Override
    public LuaValue mod(int rhs) {
        return currentValue().mod(rhs);
    }

    @Override
    public LuaValue modFrom(double lhs) {
        return currentValue().modFrom(lhs);
    }

    @Override
    public LuaValue lt(LuaValue rhs) {
        return currentValue().lt(rhs);
    }

    @Override
    public LuaValue lt(double rhs) {
        return currentValue().lt(rhs);
    }

    @Override
    public LuaValue lt(int rhs) {
        return currentValue().lt(rhs);
    }

    @Override
    public boolean lt_b(LuaValue rhs) {
        return currentValue().lt_b(rhs);
    }

    @Override
    public boolean lt_b(int rhs) {
        return currentValue().lt_b(rhs);
    }

    @Override
    public boolean lt_b(double rhs) {
        return currentValue().lt_b(rhs);
    }

    @Override
    public LuaValue lteq(LuaValue rhs) {
        return currentValue().lteq(rhs);
    }

    @Override
    public LuaValue lteq(double rhs) {
        return currentValue().lteq(rhs);
    }

    @Override
    public LuaValue lteq(int rhs) {
        return currentValue().lteq(rhs);
    }

    @Override
    public boolean lteq_b(LuaValue rhs) {
        return currentValue().lteq_b(rhs);
    }

    @Override
    public boolean lteq_b(int rhs) {
        return currentValue().lteq_b(rhs);
    }

    @Override
    public boolean lteq_b(double rhs) {
        return currentValue().lteq_b(rhs);
    }

    @Override
    public LuaValue gt(LuaValue rhs) {
        return currentValue().gt(rhs);
    }

    @Override
    public LuaValue gt(double rhs) {
        return currentValue().gt(rhs);
    }

    @Override
    public LuaValue gt(int rhs) {
        return currentValue().gt(rhs);
    }

    @Override
    public boolean gt_b(LuaValue rhs) {
        return currentValue().gt_b(rhs);
    }

    @Override
    public boolean gt_b(int rhs) {
        return currentValue().gt_b(rhs);
    }

    @Override
    public boolean gt_b(double rhs) {
        return currentValue().gt_b(rhs);
    }

    @Override
    public LuaValue gteq(LuaValue rhs) {
        return currentValue().gteq(rhs);
    }

    @Override
    public LuaValue gteq(double rhs) {
        return currentValue().gteq(rhs);
    }

    @Override
    public LuaValue gteq(int rhs) {
        return currentValue().gteq(rhs);
    }

    @Override
    public boolean gteq_b(LuaValue rhs) {
        return currentValue().gteq_b(rhs);
    }

    @Override
    public boolean gteq_b(int rhs) {
        return currentValue().gteq_b(rhs);
    }

    @Override
    public boolean gteq_b(double rhs) {
        return currentValue().gteq_b(rhs);
    }

    @Override
    public LuaValue comparemt(LuaValue tag, LuaValue op1) {
        return currentValue().comparemt(tag, op1);
    }

    @Override
    public int strcmp(LuaValue rhs) {
        return currentValue().strcmp(rhs);
    }

    @Override
    public int strcmp(LuaString rhs) {
        return currentValue().strcmp(rhs);
    }

    @Override
    public LuaValue concat(LuaValue rhs) {
        return currentValue().concat(rhs);
    }

    @Override
    public LuaValue concatTo(LuaValue lhs) {
        return currentValue().concatTo(lhs);
    }

    @Override
    public LuaValue concatTo(LuaNumber lhs) {
        return currentValue().concatTo(lhs);
    }

    @Override
    public LuaValue concatTo(LuaString lhs) {
        return currentValue().concatTo(lhs);
    }

    @Override
    public Buffer buffer() {
        return currentValue().buffer();
    }

    @Override
    public Buffer concat(Buffer rhs) {
        return currentValue().concat(rhs);
    }

    @Override
    public LuaValue concatmt(LuaValue rhs) {
        return currentValue().concatmt(rhs);
    }

    @Override
    public LuaValue and(LuaValue rhs) {
        return currentValue().and(rhs);
    }

    @Override
    public LuaValue or(LuaValue rhs) {
        return currentValue().or(rhs);
    }

    @Override
    public boolean testfor_b(LuaValue limit, LuaValue step) {
        return currentValue().testfor_b(limit, step);
    }

    @Override
    public LuaString strvalue() {
        return currentValue().strvalue();
    }

    @Override
    public LuaValue strongvalue() {
        return currentValue().strongvalue();
    }

    @Override
    public LuaValue metatag(LuaValue tag) {
        return currentValue().metatag(tag);
    }

    @Override
    public Varargs onInvoke(Varargs args) {
        return currentValue().onInvoke(args);
    }

    @Override
    public void initupvalue1(LuaValue env) {
        currentValue().initupvalue1(env);
    }

    @Override
    public Varargs subargs(int start) {
        return currentValue().subargs(start);
    }

    @Override
    public Varargs eval() {
        return currentValue().eval();
    }

    @Override
    public boolean isTailcall() {
        return currentValue().isTailcall();
    }

    @Override
    public int type(int i) {
        return currentValue().type(i);
    }

    @Override
    public boolean isnil(int i) {
        return currentValue().isnil(i);
    }

    @Override
    public boolean isfunction(int i) {
        return currentValue().isfunction(i);
    }

    @Override
    public boolean isnumber(int i) {
        return currentValue().isnumber(i);
    }

    @Override
    public boolean isstring(int i) {
        return currentValue().isstring(i);
    }

    @Override
    public boolean istable(int i) {
        return currentValue().istable(i);
    }

    @Override
    public boolean isthread(int i) {
        return currentValue().isthread(i);
    }

    @Override
    public boolean isuserdata(int i) {
        return currentValue().isuserdata(i);
    }

    @Override
    public boolean isvalue(int i) {
        return currentValue().isvalue(i);
    }

    @Override
    public boolean optboolean(int i, boolean defval) {
        return currentValue().optboolean(i, defval);
    }

    @Override
    public LuaClosure optclosure(int i, LuaClosure defval) {
        return currentValue().optclosure(i, defval);
    }

    @Override
    public double optdouble(int i, double defval) {
        return currentValue().optdouble(i, defval);
    }

    @Override
    public LuaFunction optfunction(int i, LuaFunction defval) {
        return currentValue().optfunction(i, defval);
    }

    @Override
    public int optint(int i, int defval) {
        return currentValue().optint(i, defval);
    }

    @Override
    public LuaInteger optinteger(int i, LuaInteger defval) {
        return currentValue().optinteger(i, defval);
    }

    @Override
    public long optlong(int i, long defval) {
        return currentValue().optlong(i, defval);
    }

    @Override
    public LuaNumber optnumber(int i, LuaNumber defval) {
        return currentValue().optnumber(i, defval);
    }

    @Override
    public String optjstring(int i, String defval) {
        return currentValue().optjstring(i, defval);
    }

    @Override
    public LuaString optstring(int i, LuaString defval) {
        return currentValue().optstring(i, defval);
    }

    @Override
    public LuaTable opttable(int i, LuaTable defval) {
        return currentValue().opttable(i, defval);
    }

    @Override
    public LuaThread optthread(int i, LuaThread defval) {
        return currentValue().optthread(i, defval);
    }

    @Override
    public Object optuserdata(int i, Object defval) {
        return currentValue().optuserdata(i, defval);
    }

    @Override
    public Object optuserdata(int i, Class c, Object defval) {
        return currentValue().optuserdata(i, c, defval);
    }

    @Override
    public LuaValue optvalue(int i, LuaValue defval) {
        return currentValue().optvalue(i, defval);
    }

    @Override
    public boolean checkboolean(int i) {
        return currentValue().checkboolean(i);
    }

    @Override
    public LuaClosure checkclosure(int i) {
        return currentValue().checkclosure(i);
    }

    @Override
    public double checkdouble(int i) {
        return currentValue().checkdouble(i);
    }

    @Override
    public LuaFunction checkfunction(int i) {
        return currentValue().checkfunction(i);
    }

    @Override
    public int checkint(int i) {
        return currentValue().checkint(i);
    }

    @Override
    public LuaInteger checkinteger(int i) {
        return currentValue().checkinteger(i);
    }

    @Override
    public long checklong(int i) {
        return currentValue().checklong(i);
    }

    @Override
    public LuaNumber checknumber(int i) {
        return currentValue().checknumber(i);
    }

    @Override
    public String checkjstring(int i) {
        return currentValue().checkjstring(i);
    }

    @Override
    public LuaString checkstring(int i) {
        return currentValue().checkstring(i);
    }

    @Override
    public LuaTable checktable(int i) {
        return currentValue().checktable(i);
    }

    @Override
    public LuaThread checkthread(int i) {
        return currentValue().checkthread(i);
    }

    @Override
    public Object checkuserdata(int i) {
        return currentValue().checkuserdata(i);
    }

    @Override
    public Object checkuserdata(int i, Class c) {
        return currentValue().checkuserdata(i, c);
    }

    @Override
    public LuaValue checkvalue(int i) {
        return currentValue().checkvalue(i);
    }

    @Override
    public LuaValue checknotnil(int i) {
        return currentValue().checknotnil(i);
    }

    @Override
    public void argcheck(boolean test, int i, String msg) {
        currentValue().argcheck(test, i, msg);
    }

    @Override
    public boolean isnoneornil(int i) {
        return currentValue().isnoneornil(i);
    }

    @Override
    public boolean toboolean(int i) {
        return currentValue().toboolean(i);
    }

    @Override
    public byte tobyte(int i) {
        return currentValue().tobyte(i);
    }

    @Override
    public char tochar(int i) {
        return currentValue().tochar(i);
    }

    @Override
    public double todouble(int i) {
        return currentValue().todouble(i);
    }

    @Override
    public float tofloat(int i) {
        return currentValue().tofloat(i);
    }

    @Override
    public int toint(int i) {
        return currentValue().toint(i);
    }

    @Override
    public long tolong(int i) {
        return currentValue().tolong(i);
    }

    @Override
    public String tojstring(int i) {
        return currentValue().tojstring(i);
    }

    @Override
    public short toshort(int i) {
        return currentValue().toshort(i);
    }

    @Override
    public Object touserdata(int i) {
        return currentValue().touserdata(i);
    }

    @Override
    public Object touserdata(int i, Class c) {
        return currentValue().touserdata(i, c);
    }

    @Override
    public int type() {
        return currentValue().type();
    }

    @Override
    public String typename() {
        return currentValue().typename();
    }

}
