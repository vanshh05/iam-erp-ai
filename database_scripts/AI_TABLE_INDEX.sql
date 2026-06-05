create index idx_ai_party_status_company_id on ai_party_status(company_id);
create index idx_ai_party_status_party_name on ai_party_status(party_name);
create index idx_ai_party_status_party_type on ai_party_status(party_type);

create index idx_ai_sales_company_id on ai_sales(company_id);
create index idx_ai_sales_bill_no on ai_sales(bill_no);
create index idx_ai_sales_bill_date on ai_sales(bill_date);
create index idx_ai_sales_party_name on ai_sales(party_name);
create index idx_ai_sales_payment_mode on ai_sales(payment_mode);

create index idx_ai_daily_summary_company_id on ai_daily_summary(company_id);
create index idx_ai_daily_summary_summary_date on ai_daily_summary(summary_date);
