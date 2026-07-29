package com.collectorstools.repository;

import com.collectorstools.db.DatabaseManager;
import com.collectorstools.model.DebtorAudit;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class DebtorAuditRepository {

    private final Connection conn;

    public DebtorAuditRepository() {
        this.conn = DatabaseManager.getInstance().getConnection();
    }

    public DebtorAuditRepository(Connection conn) {
        this.conn = conn;
    }

    public void save(DebtorAudit audit) throws SQLException {
        String sql = """
            INSERT INTO debtor_audit (debtor_id, changed_at, field_name, old_value, new_value)
            VALUES (?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, audit.getDebtorId());
            ps.setString(2, audit.getChangedAt().toString());
            ps.setString(3, audit.getFieldName());
            ps.setString(4, audit.getOldValue());
            ps.setString(5, audit.getNewValue());
            ps.executeUpdate();
        }
    }

    public List<DebtorAudit> findByDebtorId(long debtorId) throws SQLException {
        List<DebtorAudit> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM debtor_audit WHERE debtor_id=? ORDER BY changed_at DESC")) {
            ps.setLong(1, debtorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    private DebtorAudit map(ResultSet rs) throws SQLException {
        String ts = rs.getString("changed_at");
        return DebtorAudit.builder()
                .id(rs.getLong("id"))
                .debtorId(rs.getLong("debtor_id"))
                .changedAt(ts != null ? LocalDateTime.parse(ts) : null)
                .fieldName(rs.getString("field_name"))
                .oldValue(rs.getString("old_value"))
                .newValue(rs.getString("new_value"))
                .build();
    }
}
