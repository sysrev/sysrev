Systematic Review
=====

For now, this just imports an xml file that's exported from EndNote, for
a list of citations.

In the future, it will do much more.

See resources/config.json. For now it is just using mongo and the data paths.

Structure
===
This project is divided among two git repositories.

* `systematic_review`
    * `/api` Manages a web interface, depends on jetty/scalatra.
    * `/core` Contains importing functionality and entity relationship functions. This way, `core` should be releasable without any dependencies on jetty, so the second repository for spark can use it.
* `systematic_review_spark`
    Contains all the spark functionality which dump databases that the web ui can reference.

Outline
==========
1. We have ~20,000 articles loaded into mongo database *SystematicReview* collection *sysrev*.  These are loaded from endnote file **PMP1C_Master.enlx**
2. We have 61 articles with binary exclusion/inclusion features **Immunotherapy Phase1_Selected screened abstracts_20160629.xlsx**
    1. *Inclusion:* True if all other exclusion criteria are false
    2. *Exclusion Not cancer*
    3. *Exclusion Not human study*
    4. *Exclusion Not clinical trial*
    5. *Exclusion Not phase 1 trial*
    6. *Exclusion Conference abstract*
3. Goal
    1. Rank all 20,000 articles for their of having feature 1 **Inclusion** = true
4. Approach
    1. Create classifier for **Inclusion** feature
        1. Instance based learning
            1. Article to vector (word count vector works)
                1. tf-idf vector
                2. custom features (isCancerMentioned, isHumanMentioned, timesCancerMentioned)

            2. vector similarity metric
            3. IBL algorithm
        2.  
    3. Algorithm improvement approach
        1. Human label papers with closest to 50% probability of **Inclusion** = true