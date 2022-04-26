(ns com.verybigthings.funicular.test-fixtures)

(def dictionary {:api {:app/word "api"
                       :app/definition "A set of functions and procedures allowing the creation of applications that access the features or data of an operating system, application, or other service."
                       :app/related ["graphql", "restful api"]}
                 :restful-api {:app/word "restful api"
                               :app/definition "Web service API that adheres to the REST architectural constraints"
                               :app/related ["soap"]}
                 :graphql {:app/word "graphql"
                           :app/definition "GraphQL is a query language for your API, and a server-side runtime for executing queries using a type system you define for your data."
                           :app/related ["funicular"]}
                 :funicular {:app/word "funicular"
                             :app/definition "A tool for tight integration of Clojure full stack (backend+frontend) apps."
                             :app/related ["api"]}})