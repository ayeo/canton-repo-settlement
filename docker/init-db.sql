-- One database per Canton node: nodes sharing one would clash on migrations.
CREATE DATABASE canton_sequencer OWNER canton;
CREATE DATABASE canton_mediator OWNER canton;
CREATE DATABASE canton_icsd OWNER canton;
CREATE DATABASE canton_centralbank OWNER canton;
CREATE DATABASE canton_alpha OWNER canton;
CREATE DATABASE canton_bravo OWNER canton;
CREATE DATABASE canton_charlie OWNER canton;
