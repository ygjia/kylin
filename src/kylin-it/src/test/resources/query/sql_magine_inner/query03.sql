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

-- ISSUE #2138

SELECT
 test_cal_dt.week_beg_dt
 ,test_category_groupings.meta_categ_name
 ,test_category_groupings.categ_lvl2_name
 ,test_category_groupings.categ_lvl3_name
 ,sum(test_kylin_fact.price) as GMV
 , count(*) as trans_cnt
 FROM test_kylin_fact
 , edw.test_cal_dt as test_cal_dt
 ,test_category_groupings
 ,edw.test_sites as test_sites
 where test_kylin_fact.leaf_categ_id = test_category_groupings.leaf_categ_id AND test_kylin_fact.lstg_site_id = test_category_groupings.site_id
 AND test_kylin_fact.lstg_site_id = test_sites.site_id
 AND test_kylin_fact.cal_dt = test_cal_dt.cal_dt
 group by test_cal_dt.week_beg_dt
 ,test_category_groupings.meta_categ_name
 ,test_category_groupings.categ_lvl2_name
 ,test_category_groupings.categ_lvl3_name
