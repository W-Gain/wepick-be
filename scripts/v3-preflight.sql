-- Read-only preflight checks for existing data before applying V3.
-- Both result sets must be empty before proceeding.

SELECT topic_id, label, COUNT(*) AS duplicate_count
FROM topic_options
WHERE topic_id IS NOT NULL
GROUP BY topic_id, label
HAVING COUNT(*) > 1;

SELECT
    v.vote_id,
    v.topic_id AS vote_topic_id,
    v.option_id,
    o.topic_id AS option_topic_id,
    CASE
        WHEN o.option_id IS NULL THEN 'OPTION_NOT_FOUND'
        WHEN o.topic_id IS NULL THEN 'OPTION_TOPIC_IS_NULL'
        ELSE 'TOPIC_MISMATCH'
    END AS issue
FROM votes AS v
LEFT JOIN topic_options AS o ON o.option_id = v.option_id
WHERE o.option_id IS NULL
   OR o.topic_id IS NULL
   OR o.topic_id <> v.topic_id;
