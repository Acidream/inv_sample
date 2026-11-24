package com.example.investments.adapter.persistence;

import com.example.investments.application.port.Instruments;
import com.example.investments.domain.Instrument;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcInstruments implements Instruments {

    private final JdbcClient jdbc;

    JdbcInstruments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instrument> find(String isin) {
        return jdbc.sql("select * from instrument where isin = :isin").param("isin", isin)
                .query(JdbcInstruments::map).optional();
    }

    @Override
    public List<Instrument> all() {
        return jdbc.sql("select * from instrument order by name").query(JdbcInstruments::map).list();
    }

    private static Instrument map(ResultSet row, int n) throws SQLException {
        Timestamp offSale = row.getTimestamp("off_sale_from");
        return new Instrument(row.getString("isin"), row.getString("name"),
                Instrument.Type.valueOf(row.getString("type")), offSale == null ? null : offSale.toInstant());
    }
}
