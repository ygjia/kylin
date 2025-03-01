--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
--  Count number of orders each customer has, then group by number of orders and count number of customers.
--
--  The result is not exactly the same as hive due to
--  1. Condition o_comment not like '%unusual%accounts%' is moved from "left join on" to "where".
--     Causes customers having 0 orders are filtered out and not reported.
--  2. HLL count distinct yields approximate result.

select
	c_count,
	count(*) as custdist
from
	(
		select
			c_custkey,
			count(distinct o_orderkey) as c_count
		from
			customer left outer join v_orders on
				c_custkey = o_custkey
		where o_comment not like '%unusual%accounts%'
		group by
			c_custkey
	) c_orders
group by
	c_count
order by
	custdist desc,
	c_count desc;
