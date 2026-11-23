-- Taking a product off sale is dated, like a new version (ADR 0017, 0019). An order is decided as of
-- the time it was placed; with a plain on_sale flag, an order placed while the product was sold but
-- applied (or replayed) after it was taken off sale would be rejected.
--
-- A product is on sale at time t if off_sale_from is null or t < off_sale_from. Like a version, the
-- date can be set ahead of the day it takes effect, and once set it never changes.
alter table savings_product add column off_sale_from timestamptz;

-- Products already off sale: when that happened isn't recorded, so it counts from now.
update savings_product set off_sale_from = now() where not on_sale;

alter table savings_product drop column on_sale;

create function refuse_off_sale_change() returns trigger language plpgsql as $$
begin
    if old.off_sale_from is not null and new.off_sale_from is distinct from old.off_sale_from then
        raise exception 'savings_product % is off sale from %; that date cannot change', old.code, old.off_sale_from;
    end if;
    return new;
end
$$;

create trigger savings_product_off_sale_immutable
    before update on savings_product
    for each row execute function refuse_off_sale_change();
