package com.me.galchat.typehandler;

import java.lang.reflect.Type;
import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class JsonbTypeHandler extends BaseTypeHandler<Object> {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final Class<?> type;

    private final Type genericType;

    public JsonbTypeHandler(Class<?> type) {
        this(type, null);
    }

    public JsonbTypeHandler(Class<?> type, Field field) {
        this.type = type;
        this.genericType = field == null ? null : field.getGenericType();
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, toJson(parameter), Types.OTHER);
    }

    @Override
    public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Object getNullableResult(java.sql.CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private String toJson(Object parameter) {
        try {
            return OBJECT_MAPPER.writeValueAsString(parameter);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("serialize jsonb parameter failed", e);
        }
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JavaType javaType = OBJECT_MAPPER.constructType(genericType == null ? type : genericType);
            return OBJECT_MAPPER.readValue(json, javaType);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("parse jsonb result failed", e);
        }
    }
}
