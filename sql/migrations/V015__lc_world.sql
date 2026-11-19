-- SPEC-AR (docs/spec/aden-remembers.md §2): regions and towns, derived state, resource families, actuator decisions and
-- applied history, situations, Crown Law, influence spends and the PK record, the Merchant Guild and its caravans,
-- rumors and history.
--
-- One statement per ';' at the end of a line; the test that applies migrations relies on it.

CREATE TABLE lc_region (
	region_id SMALLINT NOT NULL,
	kind ENUM('TERRITORY', 'SUBREGION') NOT NULL,
	name_key VARCHAR(64) NOT NULL,
	castle_id INT NULL,
	parent_id SMALLINT NULL,
	PRIMARY KEY (region_id),
	CONSTRAINT chk_region_parent CHECK ((kind = 'TERRITORY') = (parent_id IS NULL)),
	CONSTRAINT fk_region_parent FOREIGN KEY (parent_id) REFERENCES lc_region (region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_region_atom (
	atom SMALLINT NOT NULL,
	territory_id SMALLINT NOT NULL,
	PRIMARY KEY (atom),
	CONSTRAINT fk_atom_territory FOREIGN KEY (territory_id) REFERENCES lc_region (region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_world_town (
	town_id INT NOT NULL,
	territory_id SMALLINT NOT NULL,
	neighbour_ids JSON NOT NULL,
	PRIMARY KEY (town_id),
	CONSTRAINT chk_world_town_neighbours CHECK (JSON_VALID(neighbour_ids) AND JSON_TYPE(neighbour_ids) = 'ARRAY'),
	CONSTRAINT fk_world_town_territory FOREIGN KEY (territory_id) REFERENCES lc_region (region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_subregion_spawn (
	spawn_file VARCHAR(128) NOT NULL,
	subregion_id SMALLINT NOT NULL,
	PRIMARY KEY (spawn_file),
	CONSTRAINT fk_subregion_spawn FOREIGN KEY (subregion_id) REFERENCES lc_region (region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Derived, rebuildable by replay (spec §2.2).
CREATE TABLE lc_region_state (
	region_id SMALLINT NOT NULL,
	state_key VARCHAR(48) NOT NULL,
	value INT NOT NULL,
	window_end TIMESTAMP(3) NOT NULL,
	reducer_ver SMALLINT NOT NULL,
	PRIMARY KEY (region_id, state_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_resource_family (
	family_id SMALLINT NOT NULL,
	name_key VARCHAR(64) NOT NULL,
	catch_in BOOLEAN NOT NULL DEFAULT FALSE,
	max_relocate_permille SMALLINT NOT NULL,
	max_rate_permille_day SMALLINT NOT NULL,
	recovery_days SMALLINT NOT NULL,
	PRIMARY KEY (family_id),
	CONSTRAINT chk_family_bounds CHECK (max_relocate_permille BETWEEN 0 AND 100 AND max_rate_permille_day BETWEEN 1 AND 50 AND recovery_days BETWEEN 1 AND 60)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_resource_item (
	item_id INT NOT NULL,
	family_id SMALLINT NOT NULL,
	PRIMARY KEY (item_id),
	CONSTRAINT fk_resource_family FOREIGN KEY (family_id) REFERENCES lc_resource_family (family_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_resource_protected (
	item_id INT NOT NULL,
	reason ENUM('ADENA', 'SEAL_STONE', 'HERB', 'QUEST', 'EVENT', 'BOSS_DROP') NOT NULL,
	PRIMARY KEY (item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_resource_item_unprotected BEFORE INSERT ON lc_resource_item FOR EACH ROW IF EXISTS (SELECT 1 FROM lc_resource_protected WHERE item_id = NEW.item_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_resource_item: a protected item belongs to no family'; END IF;

CREATE TRIGGER lc_resource_item_unprotected_update BEFORE UPDATE ON lc_resource_item FOR EACH ROW IF EXISTS (SELECT 1 FROM lc_resource_protected WHERE item_id = NEW.item_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_resource_item: a protected item belongs to no family'; END IF;

CREATE TRIGGER lc_resource_protected_exclusive BEFORE INSERT ON lc_resource_protected FOR EACH ROW IF EXISTS (SELECT 1 FROM lc_resource_item WHERE item_id = NEW.item_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_resource_protected: the item is in a family'; END IF;

CREATE TRIGGER lc_resource_protected_exclusive_update BEFORE UPDATE ON lc_resource_protected FOR EACH ROW IF EXISTS (SELECT 1 FROM lc_resource_item WHERE item_id = NEW.item_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_resource_protected: the item is in a family'; END IF;

-- Decisions (derived, rebuilt by replay) and the applied, append-only history (spec §2.2). RELOCATE is a share with
-- neutral 0; the other kinds are factors with neutral 1000.
CREATE TABLE lc_actuator_target (
	kind ENUM('RELOCATE', 'STOCK', 'GUILD_BUY', 'GUILD_SELL') NOT NULL,
	region_id SMALLINT NOT NULL,
	target INT NOT NULL,
	value_permille SMALLINT NOT NULL,
	decided_fact_id BIGINT NOT NULL,
	PRIMARY KEY (kind, region_id, target)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_actuator (
	application_id BINARY(16) NOT NULL,
	kind ENUM('RELOCATE', 'STOCK', 'GUILD_BUY', 'GUILD_SELL') NOT NULL,
	region_id SMALLINT NOT NULL,
	target INT NOT NULL,
	value_permille SMALLINT NOT NULL,
	min_permille SMALLINT NOT NULL,
	max_permille SMALLINT NOT NULL,
	reason_code VARCHAR(48) NOT NULL,
	situation_id BIGINT NULL,
	fact_id BIGINT NOT NULL,
	applied_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (application_id),
	KEY idx_actuator_current (kind, region_id, target, applied_at),
	CONSTRAINT chk_actuator_bounds CHECK (value_permille BETWEEN min_permille AND max_permille),
	CONSTRAINT chk_actuator_kind_bounds CHECK (CASE kind WHEN 'RELOCATE' THEN min_permille = 0 AND max_permille <= 100 WHEN 'STOCK' THEN min_permille >= 700 AND max_permille <= 1500 AND min_permille <= 1000 AND max_permille >= 1000 WHEN 'GUILD_BUY' THEN min_permille >= 1000 AND max_permille <= 2000 ELSE min_permille >= 500 AND max_permille <= 1500 AND min_permille <= 1000 AND max_permille >= 1000 END)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_actuator_frozen BEFORE UPDATE ON lc_actuator FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_actuator: history is never changed';

CREATE TRIGGER lc_actuator_kept BEFORE DELETE ON lc_actuator FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_actuator: history is never deleted';

CREATE TABLE lc_actuator_switch (
	kind ENUM('RELOCATE', 'STOCK', 'GUILD', 'CARAVAN', 'RUMOR') NOT NULL,
	enabled BOOLEAN NOT NULL,
	changed_by VARCHAR(64) NOT NULL,
	changed_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_situation (
	situation_id BIGINT NOT NULL AUTO_INCREMENT,
	kind ENUM('OVERHARVEST', 'SHORTAGE', 'TRADE_SHIFT', 'OUTLAW_PRESSURE', 'BOSS_ECOLOGY', 'WAR_EXHAUSTION', 'UNREST', 'FRONTIER') NOT NULL,
	region_id SMALLINT NOT NULL,
	state ENUM('EMERGING', 'ACTIVE', 'RECEDING', 'CLOSED') NOT NULL,
	opened_fact_id BIGINT NOT NULL,
	opened_at TIMESTAMP(3) NOT NULL,
	cause JSON NOT NULL,
	params JSON NOT NULL,
	next_eval_at TIMESTAMP(3) NULL,
	closed_at TIMESTAMP(3) NULL,
	open_key VARCHAR(40) AS (IF(state <> 'CLOSED', CONCAT(kind, '/', region_id), NULL)) PERSISTENT,
	PRIMARY KEY (situation_id),
	UNIQUE KEY uk_situation_open (open_key),
	CONSTRAINT chk_situation_closed CHECK ((state = 'CLOSED') = (closed_at IS NOT NULL)),
	CONSTRAINT chk_situation_json CHECK (JSON_VALID(cause) AND JSON_VALID(params))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_situation_born BEFORE INSERT ON lc_situation FOR EACH ROW IF NEW.state <> 'EMERGING' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_situation: a situation starts emerging'; END IF;

CREATE TABLE lc_situation_transition (
	situation_id BIGINT NOT NULL,
	seq SMALLINT NOT NULL,
	from_state ENUM('EMERGING', 'ACTIVE', 'RECEDING', 'CLOSED') NULL,
	to_state ENUM('EMERGING', 'ACTIVE', 'RECEDING', 'CLOSED') NOT NULL,
	at TIMESTAMP(3) NOT NULL,
	reason_code VARCHAR(48) NOT NULL,
	input_fact_from BIGINT NOT NULL,
	input_fact_to BIGINT NOT NULL,
	PRIMARY KEY (situation_id, seq),
	CONSTRAINT chk_transition_facts CHECK (input_fact_to >= input_fact_from),
	CONSTRAINT fk_transition_situation FOREIGN KEY (situation_id) REFERENCES lc_situation (situation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_situation_transition_machine BEFORE INSERT ON lc_situation_transition FOR EACH ROW IF NOT IFNULL(((NEW.seq = 1 AND NEW.from_state IS NULL AND NEW.to_state = 'EMERGING') OR (NEW.seq > 1 AND NEW.seq = (SELECT IFNULL(MAX(seq), 0) + 1 FROM lc_situation_transition WHERE situation_id = NEW.situation_id) AND NEW.from_state = (SELECT to_state FROM lc_situation_transition WHERE situation_id = NEW.situation_id AND seq = NEW.seq - 1) AND ((NEW.from_state, NEW.to_state) IN (('EMERGING', 'ACTIVE'), ('EMERGING', 'CLOSED'), ('ACTIVE', 'RECEDING'), ('RECEDING', 'ACTIVE'), ('RECEDING', 'CLOSED')) OR (NEW.from_state = 'ACTIVE' AND NEW.to_state = 'CLOSED' AND NEW.reason_code IN ('FROZEN', 'DISABLED'))))), FALSE) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_situation_transition: not a transition of the situation machine'; END IF;

CREATE TRIGGER lc_situation_moved BEFORE UPDATE ON lc_situation FOR EACH ROW IF OLD.state = 'CLOSED' OR NEW.kind <> OLD.kind OR NEW.region_id <> OLD.region_id OR (NEW.state <> OLD.state AND NOT EXISTS (SELECT 1 FROM lc_situation_transition t WHERE t.situation_id = OLD.situation_id AND t.from_state = OLD.state AND t.to_state = NEW.state AND t.seq = (SELECT MAX(seq) FROM lc_situation_transition WHERE situation_id = OLD.situation_id))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_situation: a state changes only with its newest transition row'; END IF;

CREATE TRIGGER lc_situation_transition_frozen BEFORE UPDATE ON lc_situation_transition FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_situation_transition: transitions are never changed';

CREATE TRIGGER lc_situation_transition_kept BEFORE DELETE ON lc_situation_transition FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_situation_transition: transitions are never deleted';

-- Crown Law (spec §2.4): one enacted law per (castle, kind), so at most six; the configuration may allow fewer.
CREATE TABLE lc_law (
	law_id BIGINT NOT NULL AUTO_INCREMENT,
	castle_id INT NOT NULL,
	kind ENUM('BOUNTY_ENDOWMENT', 'MARKET_CHARTER', 'CARAVAN_PROTECTION', 'AMNESTY', 'GATEKEEPER_LEVY', 'OPEN_ROADS') NOT NULL,
	state ENUM('ENACTED', 'REPEALED', 'EXPIRED', 'EXHAUSTED') NOT NULL DEFAULT 'ENACTED',
	reign_key VARCHAR(48) NOT NULL,
	enacted_by INT NOT NULL,
	enacted_at TIMESTAMP(3) NOT NULL,
	proclaimed_fact_id BIGINT NULL,
	params JSON NOT NULL,
	custody_id INT NULL,
	ended_at TIMESTAMP(3) NULL,
	end_reason VARCHAR(48) NULL,
	active_key VARCHAR(40) AS (IF(state = 'ENACTED', CONCAT(castle_id, '/', kind), NULL)) PERSISTENT,
	amnesty_key VARCHAR(64) AS (IF(kind = 'AMNESTY', reign_key, NULL)) PERSISTENT,
	PRIMARY KEY (law_id),
	UNIQUE KEY uk_law_active (active_key),
	UNIQUE KEY uk_law_amnesty (amnesty_key),
	KEY idx_law_castle (castle_id, state),
	CONSTRAINT chk_law_end CHECK ((state = 'ENACTED') = (ended_at IS NULL AND end_reason IS NULL)),
	CONSTRAINT chk_law_funding CHECK ((kind = 'AMNESTY' OR kind = 'GATEKEEPER_LEVY') = (custody_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_law_kept BEFORE DELETE ON lc_law FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_law: laws are never deleted';

CREATE TABLE lc_influence_spend (
	spend_id BIGINT NOT NULL AUTO_INCREMENT,
	territory_id SMALLINT NOT NULL,
	clan_id INT NOT NULL,
	reign_key VARCHAR(48) NOT NULL,
	amount INT NOT NULL,
	law_id BIGINT NOT NULL,
	fact_id BIGINT NOT NULL,
	PRIMARY KEY (spend_id),
	UNIQUE KEY uk_influence_spend_law (law_id),
	CONSTRAINT chk_influence_spend CHECK (amount > 0),
	CONSTRAINT fk_influence_spend_law FOREIGN KEY (law_id) REFERENCES lc_law (law_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_influence_spend_frozen BEFORE UPDATE ON lc_influence_spend FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_influence_spend: spends are never changed';

CREATE TRIGGER lc_influence_spend_kept BEFORE DELETE ON lc_influence_spend FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_influence_spend: spends are never deleted';

-- Derived: each character's last innocent kill, for Amnesty (spec §2.4).
CREATE TABLE lc_world_pk (
	char_id INT NOT NULL,
	last_pk_fact_id BIGINT NOT NULL,
	last_pk_at TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (char_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_law_final BEFORE UPDATE ON lc_law FOR EACH ROW IF OLD.state <> 'ENACTED' OR NEW.castle_id <> OLD.castle_id OR NEW.kind <> OLD.kind OR NEW.reign_key <> OLD.reign_key OR NOT (NEW.custody_id <=> OLD.custody_id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_law: an ended law never changes, a law never changes castle, kind, reign or custody'; END IF;

-- The Merchant Guild (spec §2.5).
CREATE TABLE lc_guild_town (
	town_id INT NOT NULL,
	territory_id SMALLINT NOT NULL,
	warehouse_custody_id INT NOT NULL,
	pool_custody_id INT NOT NULL,
	PRIMARY KEY (town_id),
	UNIQUE KEY uk_guild_territory (territory_id),
	CONSTRAINT fk_guild_territory FOREIGN KEY (territory_id) REFERENCES lc_region (region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_guild_band (
	item_id INT NOT NULL,
	buy_ceiling BIGINT NOT NULL,
	sell_floor BIGINT NOT NULL,
	application_id BINARY(16) NOT NULL,
	PRIMARY KEY (item_id),
	CONSTRAINT chk_guild_band CHECK (buy_ceiling > 0 AND sell_floor > buy_ceiling),
	CONSTRAINT fk_guild_band_item FOREIGN KEY (item_id) REFERENCES lc_resource_item (item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_guild_price (
	town_id INT NOT NULL,
	item_id INT NOT NULL,
	buy_price BIGINT NOT NULL,
	sell_price BIGINT NOT NULL,
	application_id BINARY(16) NOT NULL,
	PRIMARY KEY (town_id, item_id),
	CONSTRAINT chk_guild_spread CHECK (buy_price > 0 AND sell_price > buy_price),
	CONSTRAINT fk_guild_price_town FOREIGN KEY (town_id) REFERENCES lc_guild_town (town_id),
	CONSTRAINT fk_guild_price_item FOREIGN KEY (item_id) REFERENCES lc_resource_item (item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_guild_order (
	order_id BIGINT NOT NULL AUTO_INCREMENT,
	town_id INT NOT NULL,
	item_id INT NOT NULL,
	qty INT NOT NULL,
	unit_price BIGINT NOT NULL,
	contract_id BIGINT NOT NULL,
	situation_id BIGINT NULL,
	state ENUM('POSTED', 'FILLED', 'EXPIRED') NOT NULL DEFAULT 'POSTED',
	PRIMARY KEY (order_id),
	UNIQUE KEY uk_guild_order_contract (contract_id),
	CONSTRAINT chk_guild_order CHECK (qty > 0 AND unit_price > 0),
	CONSTRAINT fk_guild_order_town FOREIGN KEY (town_id) REFERENCES lc_guild_town (town_id),
	CONSTRAINT fk_guild_order_item FOREIGN KEY (item_id) REFERENCES lc_resource_item (item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- No loop gains Adena: each item's band has its buy ceiling below its sell floor; every buy and open order price is at
-- most the ceiling and every sell price at least the floor. Each check reads the band with a locking read of another
-- table (always the latest committed row), and a band change checks the item's prices and orders the same way, so no
-- check compares rows of its own table and no isolation level can break the rule (spec §2.5).
CREATE TRIGGER lc_guild_price_band BEFORE INSERT ON lc_guild_price FOR EACH ROW BEGIN DECLARE ceiling BIGINT; DECLARE floor_price BIGINT; SELECT buy_ceiling, sell_floor INTO ceiling, floor_price FROM lc_guild_band WHERE item_id = NEW.item_id FOR UPDATE; IF ceiling IS NULL OR NEW.buy_price > ceiling OR NEW.sell_price < floor_price THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_guild_price: prices stay inside the item''s band'; END IF; END;

CREATE TRIGGER lc_guild_price_band_update BEFORE UPDATE ON lc_guild_price FOR EACH ROW BEGIN DECLARE ceiling BIGINT; DECLARE floor_price BIGINT; SELECT buy_ceiling, sell_floor INTO ceiling, floor_price FROM lc_guild_band WHERE item_id = NEW.item_id FOR UPDATE; IF ceiling IS NULL OR NEW.buy_price > ceiling OR NEW.sell_price < floor_price THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_guild_price: prices stay inside the item''s band'; END IF; END;

CREATE TRIGGER lc_guild_order_band BEFORE INSERT ON lc_guild_order FOR EACH ROW BEGIN DECLARE ceiling BIGINT; SELECT buy_ceiling INTO ceiling FROM lc_guild_band WHERE item_id = NEW.item_id FOR UPDATE; IF NEW.state = 'POSTED' AND (ceiling IS NULL OR NEW.unit_price > ceiling) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_guild_order: an order price stays at or below the item''s buy ceiling'; END IF; END;

CREATE TRIGGER lc_guild_order_state BEFORE UPDATE ON lc_guild_order FOR EACH ROW IF OLD.state <> 'POSTED' OR NEW.unit_price <> OLD.unit_price OR NEW.item_id <> OLD.item_id OR NEW.qty <> OLD.qty OR NEW.contract_id <> OLD.contract_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_guild_order: an order only moves from posted to filled or expired'; END IF;

CREATE TRIGGER lc_guild_band_change BEFORE UPDATE ON lc_guild_band FOR EACH ROW BEGIN DECLARE max_buy BIGINT; DECLARE min_sell BIGINT; DECLARE max_order BIGINT; SELECT MAX(buy_price), MIN(sell_price) INTO max_buy, min_sell FROM lc_guild_price WHERE item_id = NEW.item_id FOR UPDATE; SELECT MAX(unit_price) INTO max_order FROM lc_guild_order WHERE item_id = NEW.item_id AND state = 'POSTED' FOR UPDATE; IF NEW.item_id <> OLD.item_id OR NEW.buy_ceiling < IFNULL(GREATEST(IFNULL(max_buy, 0), IFNULL(max_order, 0)), 0) OR NEW.sell_floor > IFNULL(min_sell, NEW.sell_floor) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_guild_band: a band never leaves an existing price or open order outside it'; END IF; END;

-- Derived: the projector's view of each warehouse (spec §2.5).
CREATE TABLE lc_guild_stock (
	town_id INT NOT NULL,
	item_id INT NOT NULL,
	qty BIGINT NOT NULL,
	fact_id BIGINT NOT NULL,
	PRIMARY KEY (town_id, item_id),
	CONSTRAINT chk_guild_stock CHECK (qty >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_caravan_route (
	route_name VARCHAR(64) NOT NULL,
	origin_town INT NOT NULL,
	dest_town INT NOT NULL,
	nodes SMALLINT NOT NULL,
	scheduled_minutes SMALLINT NOT NULL,
	fort_ids JSON NOT NULL,
	PRIMARY KEY (route_name),
	CONSTRAINT chk_caravan_route CHECK (origin_town <> dest_town AND nodes >= 2 AND scheduled_minutes > 0 AND JSON_VALID(fort_ids)),
	CONSTRAINT fk_route_origin FOREIGN KEY (origin_town) REFERENCES lc_guild_town (town_id),
	CONSTRAINT fk_route_dest FOREIGN KEY (dest_town) REFERENCES lc_guild_town (town_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_caravan (
	caravan_id BIGINT NOT NULL AUTO_INCREMENT,
	route_name VARCHAR(64) NOT NULL,
	manifest_custody_id INT NOT NULL,
	state ENUM('SCHEDULED', 'EN_ROUTE', 'ARRIVED', 'RAIDED', 'RECALLED', 'SETTLED') NOT NULL DEFAULT 'SCHEDULED',
	depart_at TIMESTAMP(3) NOT NULL,
	last_node SMALLINT NOT NULL DEFAULT 0,
	arrived_at TIMESTAMP(3) NULL,
	outcome JSON NULL,
	escort_contract_id BIGINT NULL,
	PRIMARY KEY (caravan_id),
	UNIQUE KEY uk_caravan_manifest (manifest_custody_id),
	CONSTRAINT fk_caravan_route FOREIGN KEY (route_name) REFERENCES lc_caravan_route (route_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TRIGGER lc_caravan_insert BEFORE INSERT ON lc_caravan FOR EACH ROW IF NEW.state <> 'SCHEDULED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_caravan: a caravan starts scheduled'; END IF;

CREATE TRIGGER lc_caravan_transition BEFORE UPDATE ON lc_caravan FOR EACH ROW IF NEW.route_name <> OLD.route_name OR NEW.manifest_custody_id <> OLD.manifest_custody_id OR NEW.last_node < OLD.last_node OR NOT ((OLD.state, NEW.state) IN (('SCHEDULED', 'SCHEDULED'), ('SCHEDULED', 'EN_ROUTE'), ('SCHEDULED', 'RECALLED'), ('EN_ROUTE', 'EN_ROUTE'), ('EN_ROUTE', 'ARRIVED'), ('EN_ROUTE', 'RAIDED'), ('EN_ROUTE', 'RECALLED'), ('ARRIVED', 'SETTLED'), ('RAIDED', 'SETTLED'), ('RECALLED', 'SETTLED'))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'lc_caravan: not a transition of the caravan machine'; END IF;

CREATE TABLE lc_caravan_escort (
	caravan_id BIGINT NOT NULL,
	char_id INT NOT NULL,
	clan_id INT NULL,
	contract_id BIGINT NOT NULL,
	joined_at TIMESTAMP(3) NOT NULL,
	presence_seconds INT NOT NULL DEFAULT 0,
	PRIMARY KEY (caravan_id, char_id),
	CONSTRAINT chk_escort_presence CHECK (presence_seconds >= 0),
	CONSTRAINT fk_escort_caravan FOREIGN KEY (caravan_id) REFERENCES lc_caravan (caravan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Influence is derived; rumors carry only rule-allowed fields (spec §4.8); history is durable.
CREATE TABLE lc_influence (
	territory_id SMALLINT NOT NULL,
	clan_id INT NOT NULL,
	value INT NOT NULL,
	window_end TIMESTAMP(3) NOT NULL,
	PRIMARY KEY (territory_id, clan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_world_rumor (
	rumor_id BIGINT NOT NULL AUTO_INCREMENT,
	territory_id SMALLINT NOT NULL,
	kind ENUM('SITUATION', 'LAW', 'CARAVAN', 'HISTORY') NOT NULL,
	template_key VARCHAR(64) NOT NULL,
	params JSON NOT NULL,
	from_at TIMESTAMP(3) NOT NULL,
	until_at TIMESTAMP(3) NOT NULL,
	situation_id BIGINT NULL,
	PRIMARY KEY (rumor_id),
	KEY idx_rumor_live (territory_id, until_at),
	CONSTRAINT chk_rumor_life CHECK (until_at > from_at AND until_at <= from_at + INTERVAL 168 HOUR),
	CONSTRAINT chk_rumor_params CHECK (JSON_VALID(params) AND JSON_TYPE(params) = 'OBJECT')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_history_event (
	event_id BIGINT NOT NULL AUTO_INCREMENT,
	kind VARCHAR(48) NOT NULL,
	title_key VARCHAR(64) NOT NULL,
	started_at TIMESTAMP(3) NOT NULL,
	ended_at TIMESTAMP(3) NULL,
	region_id SMALLINT NULL,
	significance INT NOT NULL,
	facts JSON NOT NULL,
	publishable BOOLEAN NOT NULL DEFAULT FALSE,
	humans INT NOT NULL DEFAULT 0,
	synthetic INT NOT NULL DEFAULT 0,
	PRIMARY KEY (event_id),
	CONSTRAINT chk_history_span CHECK ((ended_at IS NULL OR ended_at >= started_at) AND humans >= 0 AND synthetic >= 0),
	CONSTRAINT chk_history_facts CHECK (JSON_VALID(facts))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_history_participant (
	event_id BIGINT NOT NULL,
	seq SMALLINT NOT NULL,
	char_id INT NULL,
	clan_id INT NULL,
	role VARCHAR(32) NOT NULL,
	origin ENUM('HUMAN', 'SYNTHETIC') NOT NULL,
	PRIMARY KEY (event_id, seq),
	CONSTRAINT chk_participant CHECK (char_id IS NOT NULL OR clan_id IS NOT NULL),
	CONSTRAINT fk_participant_event FOREIGN KEY (event_id) REFERENCES lc_history_event (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE lc_historical_record (
	record_kind VARCHAR(48) NOT NULL,
	holder_kind ENUM('CHARACTER', 'CLAN', 'ALLIANCE', 'CASTLE') NOT NULL,
	holder_ref BIGINT NOT NULL,
	value BIGINT NOT NULL,
	set_at TIMESTAMP(3) NOT NULL,
	event_id BIGINT NOT NULL,
	PRIMARY KEY (record_kind),
	CONSTRAINT fk_record_event FOREIGN KEY (event_id) REFERENCES lc_history_event (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Aden Remembers' publication types (spec §4.8; intel-service.md §4.7).
INSERT INTO lc_pub_type (pub_type, min_delay_minutes, location_related) VALUES ('world_market', 1440, TRUE), ('world_caravan', 1440, TRUE), ('world_map_words', 1440, TRUE);

INSERT INTO lc_pub_field_rule (pub_type, field, rule) VALUES ('world_market', 'family', 'TEXT_FROM_FACT'), ('world_market', 'territory', 'AREA_TERRITORY'), ('world_market', 'direction', 'TEXT_FROM_FACT'), ('world_market', 'day', 'DAY'), ('world_caravan', 'origin', 'PUBLIC_ENTITY'), ('world_caravan', 'destination', 'PUBLIC_ENTITY'), ('world_caravan', 'outcome', 'TEXT_FROM_FACT'), ('world_caravan', 'day', 'DAY'), ('world_map_words', 'territory', 'AREA_TERRITORY'), ('world_map_words', 'prosperity', 'TEXT_FROM_FACT'), ('world_map_words', 'conflict', 'TEXT_FROM_FACT'), ('world_map_words', 'week', 'DAY');
