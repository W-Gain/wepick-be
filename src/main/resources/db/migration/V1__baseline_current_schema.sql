-- Baseline of the existing JPA mappings (Spring Boot 3.5.6 / Hibernate 6.6).
-- Preserve current columns, nullability, enum types, unique constraints and foreign keys.
-- Target-product ERD changes belong in later migrations.

create table images (
    status tinyint not null,
    created_at datetime(6) not null,
    image_id bigint not null auto_increment,
    orphaned_at datetime(6),
    storage_key varchar(255) not null,
    primary key (image_id)
) engine=InnoDB;
create table post_comments (
    status tinyint not null,
    comment_id bigint not null auto_increment,
    created_at datetime(6),
    deleted_at datetime(6),
    post_id bigint not null,
    updated_at datetime(6),
    user_id bigint not null,
    content TEXT not null,
    primary key (comment_id)
) engine=InnoDB;
create table post_images (
    image_order tinyint not null,
    image_id bigint not null,
    post_id bigint not null,
    primary key (image_id, post_id)
) engine=InnoDB;
create table post_likes (
    created_at datetime(6),
    post_id bigint not null,
    user_id bigint not null,
    primary key (post_id, user_id)
) engine=InnoDB;
create table post_stats (
    comment_count integer not null,
    like_count integer not null,
    view_count integer not null,
    post_id bigint not null,
    primary key (post_id)
) engine=InnoDB;
create table posts (
    status tinyint not null,
    created_at datetime(6),
    deleted_at datetime(6),
    post_id bigint not null auto_increment,
    updated_at datetime(6),
    user_id bigint not null,
    content TEXT not null,
    title varchar(255) not null,
    primary key (post_id)
) engine=InnoDB;
create table topic_options (
    option_id bigint not null auto_increment,
    topic_id bigint,
    vote_count bigint not null,
    description varchar(255),
    text varchar(255) not null,
    label enum ('A','B') not null,
    primary key (option_id)
) engine=InnoDB;
create table topics (
    target_date date not null,
    created_at datetime(6),
    topic_id bigint not null auto_increment,
    updated_at datetime(6),
    description TEXT,
    title varchar(255) not null,
    status enum ('CLOSED','OPEN') not null,
    primary key (topic_id)
) engine=InnoDB;
create table users (
    status tinyint not null,
    created_at datetime(6),
    deleted_at datetime(6),
    profile_image_id bigint,
    updated_at datetime(6),
    user_id bigint not null auto_increment,
    email varchar(255) not null,
    nickname varchar(255) not null,
    password varchar(255) not null,
    primary key (user_id)
) engine=InnoDB;
create table votes (
    created_at datetime(6),
    option_id bigint not null,
    topic_id bigint not null,
    updated_at datetime(6),
    user_id bigint not null,
    vote_id bigint not null auto_increment,
    primary key (vote_id)
) engine=InnoDB;
alter table users add constraint UK4unapofvpijp79n4j3sheoun7 unique (profile_image_id);
alter table users add constraint UK6dotkott2kjsp8vw4d0m25fb7 unique (email);
alter table users add constraint UK2ty1xmrrgtn89xt7kyxx6ta7h unique (nickname);
alter table votes add constraint UK960tatcjmaulgtyim8o3mrljq unique (topic_id, user_id);
alter table post_comments add constraint FKaawaqxjs3br8dw5v90w7uu514 foreign key (post_id) references posts (post_id);
alter table post_comments add constraint FKsnxoecngu89u3fh4wdrgf0f2g foreign key (user_id) references users (user_id);
alter table post_images add constraint FKedfly3oxyjk3wbj53tox5v6rh foreign key (image_id) references images (image_id);
alter table post_images add constraint FKo1i5va2d8de9mwq727vxh0s05 foreign key (post_id) references posts (post_id);
alter table post_likes add constraint FKa5wxsgl4doibhbed9gm7ikie2 foreign key (post_id) references posts (post_id);
alter table post_likes add constraint FKkgau5n0nlewg6o9lr4yibqgxj foreign key (user_id) references users (user_id);
alter table post_stats add constraint FK4cjiqemioe1o57h1pd4kuem3v foreign key (post_id) references posts (post_id);
alter table posts add constraint FK5lidm6cqbc7u4xhqpxm898qme foreign key (user_id) references users (user_id);
alter table topic_options add constraint FK2rpii9n5f2j80yabwoakx5air foreign key (topic_id) references topics (topic_id);
alter table users add constraint FKe9m3buhrd541cj89320px18t4 foreign key (profile_image_id) references images (image_id);
alter table votes add constraint FKk88wfkimxrweip9aeqax2tq10 foreign key (option_id) references topic_options (option_id);
alter table votes add constraint FK64rthokp01qie35110defje4c foreign key (topic_id) references topics (topic_id);
alter table votes add constraint FKli4uj3ic2vypf5pialchj925e foreign key (user_id) references users (user_id);

-- Spring Session JDBC 3.5.2: bundled schema-mysql.sql, including principal lookup.
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BLOB NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
