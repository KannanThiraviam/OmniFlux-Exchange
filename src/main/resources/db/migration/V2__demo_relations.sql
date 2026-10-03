-- Every statement is idempotent: this migration runs against an EMPTY database
-- (Flyway applies V1 then V2) and against the COMPOSE database (baselined at V1
-- by init-db, then V2 applied). Same end state either way.
ALTER TABLE mock_orders ADD COLUMN IF NOT EXISTS note TEXT;
ALTER TABLE mock_orders ADD COLUMN IF NOT EXISTS country TEXT;

-- 200 TEXT columns: c001 … c200. The matrix's wide cells set row width by
-- choosing how much the seeder puts in each.
CREATE TABLE IF NOT EXISTS mock_wide (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  c001 TEXT, c002 TEXT, c003 TEXT, c004 TEXT, c005 TEXT, c006 TEXT, c007 TEXT, c008 TEXT, c009 TEXT, c010 TEXT,
  c011 TEXT, c012 TEXT, c013 TEXT, c014 TEXT, c015 TEXT, c016 TEXT, c017 TEXT, c018 TEXT, c019 TEXT, c020 TEXT,
  c021 TEXT, c022 TEXT, c023 TEXT, c024 TEXT, c025 TEXT, c026 TEXT, c027 TEXT, c028 TEXT, c029 TEXT, c030 TEXT,
  c031 TEXT, c032 TEXT, c033 TEXT, c034 TEXT, c035 TEXT, c036 TEXT, c037 TEXT, c038 TEXT, c039 TEXT, c040 TEXT,
  c041 TEXT, c042 TEXT, c043 TEXT, c044 TEXT, c045 TEXT, c046 TEXT, c047 TEXT, c048 TEXT, c049 TEXT, c050 TEXT,
  c051 TEXT, c052 TEXT, c053 TEXT, c054 TEXT, c055 TEXT, c056 TEXT, c057 TEXT, c058 TEXT, c059 TEXT, c060 TEXT,
  c061 TEXT, c062 TEXT, c063 TEXT, c064 TEXT, c065 TEXT, c066 TEXT, c067 TEXT, c068 TEXT, c069 TEXT, c070 TEXT,
  c071 TEXT, c072 TEXT, c073 TEXT, c074 TEXT, c075 TEXT, c076 TEXT, c077 TEXT, c078 TEXT, c079 TEXT, c080 TEXT,
  c081 TEXT, c082 TEXT, c083 TEXT, c084 TEXT, c085 TEXT, c086 TEXT, c087 TEXT, c088 TEXT, c089 TEXT, c090 TEXT,
  c091 TEXT, c092 TEXT, c093 TEXT, c094 TEXT, c095 TEXT, c096 TEXT, c097 TEXT, c098 TEXT, c099 TEXT, c100 TEXT,
  c101 TEXT, c102 TEXT, c103 TEXT, c104 TEXT, c105 TEXT, c106 TEXT, c107 TEXT, c108 TEXT, c109 TEXT, c110 TEXT,
  c111 TEXT, c112 TEXT, c113 TEXT, c114 TEXT, c115 TEXT, c116 TEXT, c117 TEXT, c118 TEXT, c119 TEXT, c120 TEXT,
  c121 TEXT, c122 TEXT, c123 TEXT, c124 TEXT, c125 TEXT, c126 TEXT, c127 TEXT, c128 TEXT, c129 TEXT, c130 TEXT,
  c131 TEXT, c132 TEXT, c133 TEXT, c134 TEXT, c135 TEXT, c136 TEXT, c137 TEXT, c138 TEXT, c139 TEXT, c140 TEXT,
  c141 TEXT, c142 TEXT, c143 TEXT, c144 TEXT, c145 TEXT, c146 TEXT, c147 TEXT, c148 TEXT, c149 TEXT, c150 TEXT,
  c151 TEXT, c152 TEXT, c153 TEXT, c154 TEXT, c155 TEXT, c156 TEXT, c157 TEXT, c158 TEXT, c159 TEXT, c160 TEXT,
  c161 TEXT, c162 TEXT, c163 TEXT, c164 TEXT, c165 TEXT, c166 TEXT, c167 TEXT, c168 TEXT, c169 TEXT, c170 TEXT,
  c171 TEXT, c172 TEXT, c173 TEXT, c174 TEXT, c175 TEXT, c176 TEXT, c177 TEXT, c178 TEXT, c179 TEXT, c180 TEXT,
  c181 TEXT, c182 TEXT, c183 TEXT, c184 TEXT, c185 TEXT, c186 TEXT, c187 TEXT, c188 TEXT, c189 TEXT, c190 TEXT,
  c191 TEXT, c192 TEXT, c193 TEXT, c194 TEXT, c195 TEXT, c196 TEXT, c197 TEXT, c198 TEXT, c199 TEXT, c200 TEXT
);

-- init-db's pre-Flyway revisions created a schema_version table, and a compose
-- volume first booted on one of those still has it. Flyway owns history now,
-- so the obsolete table goes -- from fresh databases as much as old volumes.
DROP TABLE IF EXISTS schema_version;

-- PostgREST caches the schema at startup. Without this, every relation added
-- here 404s from the Data API until the container is restarted -- which looks
-- exactly like an allowlist bug and is not one. (Kept last: it is a broadcast,
-- not schema change; nothing may follow it.)
NOTIFY pgrst, 'reload schema';
