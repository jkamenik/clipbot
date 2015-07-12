CREATE TABLE `retro_entries` (
       id              INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
       room            VARCHAR(255),
       author          VARCHAR(255),
       entry_type_name VARCHAR(50),
       msg             VARCHAR(255),
       created_at      TIMESTAMP);

CREATE INDEX `retro_entries_created_at_index` ON `retro_entries` (created_at);
CREATE INDEX `retro_entries_author_index` ON `retro_entries` (author);
CREATE INDEX `retro_entries_room_index` ON `retro_entries` (room);

CREATE TABLE `retro_sprints` (
       id         INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
       room       VARCHAR(255),
       author     VARCHAR(255),
       created_at TIMESTAMP);

CREATE INDEX `retro_dates_created_at_index` ON `retro_sprints` (created_at);
CREATE INDEX `retro_dates_author_index` ON `retro_sprints` (author);
CREATE INDEX `retro_dates_room_index` ON `retro_sprints` (room);
