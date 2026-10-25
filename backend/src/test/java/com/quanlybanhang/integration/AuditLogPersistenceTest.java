package com.quanlybanhang.integration;

import com.quanlybanhang.model.AuditLog;
import com.quanlybanhang.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql=false, properties={"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
class AuditLogPersistenceTest {
    @Autowired AuditLogRepository logs;
    @Autowired TestEntityManager entities;

    @Test void fullWarehouseActionNamesSurvivePersistenceWithoutTruncation() {
        for (String action : new String[]{"ASSIGN_PRODUCT_LOCATION", "REMOVE_PRODUCT_LOCATION", "EXPIRED_LOT_EXPORT_OVERRIDE"}) {
            var log=logs.saveAndFlush(AuditLog.builder().entityType("WAREHOUSE").entityId("test-only")
                    .action(action).username("audit-persistence-test").reason("Isolated H2 test").build());
            Long id=log.getId();entities.clear();
            assertThat(logs.findById(id).orElseThrow().getAction()).isEqualTo(action);
        }
    }
}
