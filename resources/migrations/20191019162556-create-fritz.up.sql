create table if not exists device (
  name VARCHAR,
  identifier VARCHAR,
  PRIMARY KEY (identifier)
);

--;;

create table if not exists temperature (
  device_id VARCHAR NOT NULL REFERENCES device(identifier) ON DELETE CASCADE,
  temp float(1),
  time TIMESTAMP
);

--;;

create table if not exists watts (
  device_id VARCHAR NOT NULL REFERENCES device(identifier) ON DELETE CASCADE,
  watt float(2),
  time TIMESTAMP
);

--;;

CREATE INDEX watts_device_time ON watts (device_id, time);

--;;

CREATE INDEX temp_device_time ON temperature (device_id, time);
