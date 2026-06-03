package u3123004589.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import u3123004589.common.Constant;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(Constant.UserStatus.class)
@MappedJdbcTypes(JdbcType.INTEGER)
public class UserStatusTypeHandler extends BaseTypeHandler<Constant.UserStatus> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Constant.UserStatus param, JdbcType jdbcType) throws SQLException {
        ps.setInt(i, param.getStatus());
    }

    @Override
    public Constant.UserStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int v = rs.getInt(columnName);
        return Constant.UserStatus.fromValue(v);
    }

    @Override
    public Constant.UserStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int v = rs.getInt(columnIndex);
        return Constant.UserStatus.fromValue(v);
    }

    @Override
    public Constant.UserStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int v = cs.getInt(columnIndex);
        return Constant.UserStatus.fromValue(v);
    }
}
