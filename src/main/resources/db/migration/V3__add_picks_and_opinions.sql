-- Expand Pick publishing, review, vote, and opinion storage without changing
-- the columns used by the current entities and API.
ALTER TABLE topics
    ADD COLUMN normalized_title VARCHAR(255) NULL,
    ADD COLUMN category_code VARCHAR(30) NULL,
    ADD COLUMN content_revision INT NOT NULL DEFAULT 1,
    ADD COLUMN scheduled_kst_date DATE NULL,
    ADD COLUMN published_at DATETIME(6) NULL,
    ADD COLUMN created_by_user_id BIGINT NULL,
    ADD UNIQUE KEY UK_topics_scheduled_kst_date (scheduled_kst_date),
    ADD CONSTRAINT FK_topics_created_by_user FOREIGN KEY (created_by_user_id)
        REFERENCES users (user_id) ON DELETE NO ACTION ON UPDATE NO ACTION;

ALTER TABLE topic_options
    ADD UNIQUE KEY UK_topic_options_topic_option (topic_id, option_id),
    ADD UNIQUE KEY UK_topic_options_topic_label (topic_id, label);

ALTER TABLE votes
    ADD COLUMN anonymous_voter_id BIGINT NULL,
    ADD UNIQUE KEY UK_votes_topic_anonymous (topic_id, anonymous_voter_id),
    ADD UNIQUE KEY UK_votes_id_user (vote_id, user_id),
    ADD KEY IX_votes_topic_option (topic_id, option_id),
    ADD KEY IX_votes_user_created_vote (user_id, created_at, vote_id),
    ADD KEY IX_votes_anonymous_topic (anonymous_voter_id, topic_id),
    ADD CONSTRAINT FK_votes_anonymous_voter FOREIGN KEY (anonymous_voter_id)
        REFERENCES anonymous_voters (anonymous_voter_id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    ADD CONSTRAINT FK_votes_topic_option FOREIGN KEY (topic_id, option_id)
        REFERENCES topic_options (topic_id, option_id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    ADD CONSTRAINT CHK_votes_exactly_one_subject
        CHECK ((user_id IS NULL) <> (anonymous_voter_id IS NULL)) ENFORCED;

CREATE TABLE topic_reviews (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    content_revision INT NOT NULL,
    reviewer_user_id BIGINT NOT NULL,
    decision ENUM ('APPROVED', 'REJECTED') NOT NULL,
    memo TEXT NULL,
    reviewed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (review_id),
    CONSTRAINT FK_topic_reviews_topic FOREIGN KEY (topic_id)
        REFERENCES topics (topic_id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_topic_reviews_reviewer FOREIGN KEY (reviewer_user_id)
        REFERENCES users (user_id) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB;

CREATE TABLE topic_review_checks (
    review_check_id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    criterion_code VARCHAR(50) NOT NULL,
    passed BOOLEAN NOT NULL,
    PRIMARY KEY (review_check_id),
    CONSTRAINT UK_topic_review_checks_review_criterion UNIQUE (review_id, criterion_code),
    CONSTRAINT FK_topic_review_checks_review FOREIGN KEY (review_id)
        REFERENCES topic_reviews (review_id) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB;

CREATE TABLE topic_status_events (
    status_event_id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    from_status ENUM ('DRAFT', 'APPROVED', 'SCHEDULED', 'PUBLISHED', 'REJECTED', 'HIDDEN') NULL,
    to_status ENUM ('DRAFT', 'APPROVED', 'SCHEDULED', 'PUBLISHED', 'REJECTED', 'HIDDEN') NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (status_event_id),
    CONSTRAINT FK_topic_status_events_topic FOREIGN KEY (topic_id)
        REFERENCES topics (topic_id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_topic_status_events_actor FOREIGN KEY (actor_user_id)
        REFERENCES users (user_id) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB;

CREATE TABLE pick_assignments (
    pick_assignment_id BIGINT NOT NULL AUTO_INCREMENT,
    topic_id BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    is_fallback BOOLEAN NOT NULL DEFAULT FALSE,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN ended_at IS NULL THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (pick_assignment_id),
    CONSTRAINT UK_pick_assignments_active_slot UNIQUE (active_slot),
    KEY IX_pick_assignments_ended (ended_at, pick_assignment_id),
    KEY IX_pick_assignments_topic_ended (topic_id, ended_at, pick_assignment_id),
    CONSTRAINT FK_pick_assignments_topic FOREIGN KEY (topic_id)
        REFERENCES topics (topic_id) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB;

CREATE TABLE opinions (
    opinion_id BIGINT NOT NULL AUTO_INCREMENT,
    vote_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    body VARCHAR(300) NOT NULL,
    status ENUM ('ACTIVE', 'DELETED', 'HIDDEN') NOT NULL,
    like_count INT NOT NULL DEFAULT 0,
    active_vote_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN vote_id ELSE NULL END
    ) STORED,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (opinion_id),
    CONSTRAINT UK_opinions_active_vote UNIQUE (active_vote_id),
    KEY IX_opinions_vote_status_like_created (vote_id, status, like_count, created_at, opinion_id),
    KEY IX_opinions_status_created (status, created_at, opinion_id),
    CONSTRAINT FK_opinions_vote_author FOREIGN KEY (vote_id, author_user_id)
        REFERENCES votes (vote_id, user_id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_opinions_author FOREIGN KEY (author_user_id)
        REFERENCES users (user_id) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB;

CREATE TABLE opinion_likes (
    opinion_like_id BIGINT NOT NULL AUTO_INCREMENT,
    opinion_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (opinion_like_id),
    CONSTRAINT UK_opinion_likes_opinion_user UNIQUE (opinion_id, user_id),
    CONSTRAINT FK_opinion_likes_opinion FOREIGN KEY (opinion_id)
        REFERENCES opinions (opinion_id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_opinion_likes_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB;

CREATE TABLE opinion_moderation_events (
    moderation_event_id BIGINT NOT NULL AUTO_INCREMENT,
    opinion_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    action ENUM ('HIDE', 'UNHIDE') NOT NULL,
    reason VARCHAR(300) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (moderation_event_id),
    KEY IX_opinion_moderation_events_opinion_occurred (opinion_id, occurred_at),
    CONSTRAINT FK_opinion_moderation_events_opinion FOREIGN KEY (opinion_id)
        REFERENCES opinions (opinion_id) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT FK_opinion_moderation_events_actor FOREIGN KEY (actor_user_id)
        REFERENCES users (user_id) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB;
