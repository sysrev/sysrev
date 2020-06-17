(ns sysrev.test.graphql.mutation)

;; test import article filter url -- which project is giving us problems?
;; https://sysrev.com/u/1668/p/31635/add-articles -> sould have 12
;;
;; https://sysrev.com/u/1668/p/31464/articles?offset=0&sort-by=content-updated&sort-dir=desc&filters=%5B%7B%22has-label%22%3A%7B%22confirmed%22%3Atrue%2C%22label-id%22%3A%22eb51a19d-79bd-44b0-a7e6-179a5f1f9ba4%22%2C%22values%22%3A%5Btrue%5D%7D%7D%5D
;; into https://sysrev.com/u/1668/p/31635/add-articles
;;
;; https://sysrev.com/u/1668/p/31636/add-articles -> should have 10
;;
;; https://sysrev.com/u/1668/p/31465/articles?offset=0&sort-by=content-updated&sort-dir=desc&filters=%5B%7B%22has-label%22%3A%7B%22confirmed%22%3Atrue%2C%22label-id%22%3A%22c1a57463-d9e9-4156-b69d-967730dda038%22%2C%22values%22%3A%5Btrue%5D%7D%7D%5D
;; into https://sysrev.com/u/1668/p/31636/add-articles

;; importArticles -- using a query string from datasource

;; importDataset -- import a test dataset from datasource

;; importDatasource -- import a test datasource from datasource

;; importDatasourceFlattened -- import a test flattened datasource from datasource
