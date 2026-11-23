package com.example.savings.adapter.persistence;

import com.example.savings.application.port.Products;
import com.example.savings.domain.PayoutFrequency;
import com.example.savings.domain.SavingsProduct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@link Products} on the {@code savings_product} reference tables (V7). */
@Component
class JdbcProducts implements Products {

    private static final String COLUMNS = "p.code, p.name, v.version, v.annual_rate, v.payout_frequency";
    private static final String FROM = """
             from savings_product p join savings_product_version v on v.product_code = p.code
            """;
    private static final String SELECT = "select " + COLUMNS + FROM;

    private record Key(String code, int version) {
    }

    private final JdbcClient jdbc;
    /** Published versions never change (a trigger refuses it), so caching them is safe. */
    private final Map<Key, SavingsProduct> versions = new ConcurrentHashMap<>();

    JdbcProducts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SavingsProduct> onSale(String code, Instant at) {
        return jdbc.sql(SELECT + """
                        where p.code = :code and (p.off_sale_from is null or :at < p.off_sale_from)
                          and v.valid_from <= :at
                        order by v.valid_from desc, v.version desc
                        limit 1
                        """)
                .param("code", code)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query(JdbcProducts::map)
                .optional();
    }

    @Override
    public List<SavingsProduct> allOnSale(Instant at) {
        return jdbc.sql("select distinct on (p.code) " + COLUMNS + FROM + """
                        where (p.off_sale_from is null or :at < p.off_sale_from) and v.valid_from <= :at
                        order by p.code, v.valid_from desc, v.version desc
                        """)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query(JdbcProducts::map)
                .list();
    }

    /** The version an account was opened on, for restoring it. */
    SavingsProduct version(String code, int version) {
        return versions.computeIfAbsent(new Key(code, version), key -> jdbc.sql(SELECT + """
                        where p.code = :code and v.version = :version
                        """)
                .param("code", key.code())
                .param("version", key.version())
                .query(JdbcProducts::map)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Unknown product version " + key)));
    }

    private static SavingsProduct map(ResultSet row, int rowNum) throws SQLException {
        return new SavingsProduct(row.getString("code"), row.getInt("version"), row.getString("name"),
                row.getBigDecimal("annual_rate"), PayoutFrequency.valueOf(row.getString("payout_frequency")));
    }
}
