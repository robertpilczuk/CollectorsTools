package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.AuditEntity;
import com.collectorstools.model.AuditEntry;
import com.collectorstools.model.AuditOperation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AuditLogRepository {

    private final Connection conn;

    public AuditLogRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public AuditLogRepository(Connection conn) {
        this.conn = conn;
    }

    public AuditEntry save(AuditEntry entry) throws SQLException {
        String sql = """
            INSERT INTO audit_log (occurred_at, user_id, entity_type, entity_id, debtor_id,
                                   operation, field_name, old_value, new_value, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entry.getOccurredAt().toString());
            ps.setString(2, entry.getUserId());
            ps.setString(3, entry.getEntityType().name());
            setNullableLong(ps, 4, entry.getEntityId());
            setNullableLong(ps, 5, entry.getDebtorId());
            ps.setString(6, entry.getOperation().name());
            ps.setString(7, entry.getFieldName());
            ps.setString(8, entry.getOldValue());
            ps.setString(9, entry.getNewValue());
            ps.setString(10, entry.getReason());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) entry.setId(keys.getLong(1));
            }
        }
        return entry;
    }

    public List<AuditEntry> findByEntity(AuditEntity type, long entityId) throws SQLException {
        String sql = """
            SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ?
            ORDER BY occurred_at DESC, id DESC
        """;
        List<AuditEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setLong(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    /**
     * Pełna historia dłużnika — obejmuje też zmiany jego spraw, deklaracji i wpłat,
     * dzięki denormalizowanej kolumnie {@code debtor_id}.
     */
    public List<AuditEntry> findByDebtorId(long debtorId) throws SQLException {
        String sql = "SELECT * FROM audit_log WHERE debtor_id = ? ORDER BY occurred_at DESC, id DESC";
        List<AuditEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public List<AuditEntry> findRecent(int limit) throws SQLException {
        String sql = "SELECT * FROM audit_log ORDER BY occurred_at DESC, id DESC LIMIT ?";
        List<AuditEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public long count() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private AuditEntry map(ResultSet rs) throws SQLException {
        long entityId = rs.getLong("entity_id");
        boolean entityIdNull = rs.wasNull();
        long debtorId = rs.getLong("debtor_id");
        boolean debtorIdNull = rs.wasNull();

        return AuditEntry.builder()
                .id(rs.getLong("id"))
                .occurredAt(LocalDateTime.parse(rs.getString("occurred_at")))
                .userId(rs.getString("user_id"))
                .entityType(AuditEntity.valueOf(rs.getString("entity_type")))
                .entityId(entityIdNull ? null : entityId)
                .debtorId(debtorIdNull ? null : debtorId)
                .operation(AuditOperation.valueOf(rs.getString("operation")))
                .fieldName(rs.getString("field_name"))
                .oldValue(rs.getString("old_value"))
                .newValue(rs.getString("new_value"))
                .reason(rs.getString("reason"))
                .build();
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) ps.setLong(index, value);
        else ps.setNull(index, Types.INTEGER);
    }
}
