-- The instruments on sale at launch (ADR 0026). New instruments are new rows; one is taken off sale
-- by setting off_sale_from, so orders accepted before then are still bought.
insert into instrument (isin, name, type, off_sale_from) values
    ('IE00B4L5Y983', 'iShares Core MSCI World UCITS ETF (Acc)', 'ETF', null),
    ('IE00B5BMR087', 'iShares Core S&P 500 UCITS ETF (Acc)', 'ETF', null),
    ('IE00BK5BQT80', 'Vanguard FTSE All-World UCITS ETF (Acc)', 'ETF', null),
    ('DE0007164600', 'SAP SE', 'STOCK', null),
    ('DE0005557508', 'Deutsche Telekom AG', 'STOCK', null),
    ('DE000BAY0017', 'Bayer AG', 'STOCK', '2026-11-01T00:00:00Z');
