-- Count number of processes in each state
select count(*), state from mu_process group by state;

-- Count number of steps of each process, sorted decreasing on number
select count(*) cnt, p.process_id from mu_process p inner join mu_process_step s on (p.process_id = s.process_id) group by p.process_id order by cnt desc;

-- Show steps (and then some) for a specified process
select p.correlation_id, p.process_id, s.step_id, p.state, s.class_name, s.method_name, s.retries from mu_process p left outer join mu_process_step s on (p.process_id = s.process_id) where p.process_id = ?;

