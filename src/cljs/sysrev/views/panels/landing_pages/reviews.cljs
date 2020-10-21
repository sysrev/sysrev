(ns sysrev.views.panels.landing-pages.reviews
  (:require [sysrev.views.components.core :refer [url-link]]
            [sysrev.views.panels.landing-pages.core :refer [ReviewCard TwitterUser]]))

(defn GeneHunterReview []
  [ReviewCard
   {:href "/p/3144"
    :header "Gene Hunter" :img "/images/genehunter.jpg"
    :img-alt "Extraction of gene names from medical abstracts"
    :description
    [:p "Gene Hunter extracted gene names from medical abstracts to create a "
     [:b "named entity recognition"] " model. Learn more at "
     [url-link "https://blog.sysrev.com/simple-ner"]]
    :extra ["Tom Luechtefeld"
            [url-link "https://insilica.co"]
            [TwitterUser "tomlue"]]}])

(defn SDSReview []
  [ReviewCard
   {:href "/p/4047"
    :header "Safety Data Sheet Extraction" :img "/images/sds.jpg"
    :img-alt "safety data sheet extraction"
    :description
    [:p "SDS PDFs lock important chemical information into pdfs. "
     "Sysrev was used to extract that data into spreadsheets. Learn more at "
     [:a {:href "https://blog.sysrev.com/srg-sysrev-chemical-transparency/"}
      "blog.sysrev.com"]]
    :extra ["Daniel Mcgee"
            [:a {:href "https://sustainableresearchgroup.com"}
             "Sustainable Research Group"]]}])

(defn MangiferinReview []
  [ReviewCard
   {:href "/p/21696"
    :header "Extracting Mangiferin Effects" :img "/images/mangiferin-clustering.jpg"
    :img-alt "managed review example"
    :description
    [:p "An extraction of mangiferin (a mango extract) effects from publications. R and "
     [:a {:href "https://github.com/sysrev/RSysrev"} "RSysrev"]
     " were used to analyze results. "
     [url-link "https://blog.sysrev.com/generating-insights/"]]
    :extra ["TJ Bozada"
            "Insilica Managed Review Division"
            [:span [:i.envelope.icon] "info@insilica.co"]]}])

(defn EntogemReview []
  [ReviewCard
   {:href "/p/16612"
    :header "EntoGEM" :img "/images/entogem.jpg"
    :img-alt "EntoGEM insect population review"
    :description
    [:p "EntoGEM is a community-driven project that aims to compile evidence about
        global insect population and biodiversity status and trends. "
     [url-link "https://entogem.github.io"]]
    :extra ["Eliza Grames"
            "University of Connecticut"
            [TwitterUser "ElizaGrames"]]}])

(defn FireAustralianReview []
  [ReviewCard
   {:href "/p/24557"
    :header "Fire & Australian Invertebrates" :img "/images/bushfires.jpg"
    :img-alt "Review of inverterbrate response to australian bushfires"
    :description
    [:p "Established in response to catastrophic bushfires in Australia,
        this project seeks to understand how invertebrates respond to fire events."]
    :extra ["Manu Saunders"
            "University of New England AU"
            [TwitterUser "ManuSaunders"]]}])

(defn CancerHallmarkReview []
  [ReviewCard
   {:href "/p/3588"
    :header "Cancer Hallmark Mapping" :img "/images/tumur.jpg"
    :img-alt "National Toxicology Program Cancer Hallmarks Review"
    :description
    [:p "The aim of this project is to identify novel assays and biomarkers
         that map to the hallmarks of cancer and the key characteristics of carcinogens"]
    :extra ["Collaboration at"
            "National Toxicology Program"
            "“Converging on Cancer” workshop"]}])

(defn CovidKidneyReview []
  [ReviewCard
   {:href "/p/29506"
    :header "COVID-19 Kidney Disease" :img "/images/ckd-covid.jpg"
    :img-alt "review of COVID-19 and chronic kidney disease"
    :description
    [:p "A multinational team used Sysrev to assess the clinical characteristics and the risk
         factors associated with SARS CoV2 in patients with Chronic Kidney Disease."]
    :extra ["Ciara Keenan"
            "Associate Director - Cochrane Ireland"
            [TwitterUser "MetaEvidence"]]}])

(defn VitaminCCancerReview []
  [ReviewCard
   {:href "/p/6737"
    :header "Vitamin C Cancer Trials" :img "/images/vitc.jpg"
    :img-alt "review of ascorbate in cancer trials at clinicaltrials.gov"
    :description
    [:p "A systematic review of clinicaltrials.gov measuring drugs and dosing in ascorbate cancer trials. "
     [:a {:href "https://scholar.google.com/scholar?cluster=16503083734790425316&hl=en&oi=scholarr"}
      "Vitamin C and Cancer: An Overview of Recent Clinical Trials"]]
    :extra ["Channing Paller"
            "Johns Hopkins School of Medicine"
            "with collaborators at EMMES"]}])
