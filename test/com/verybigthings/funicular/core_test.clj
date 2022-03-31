(ns com.verybigthings.funicular.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [com.verybigthings.funicular.core :as core]
            [malli.core :as m]
            [expound.alpha :as expound]))

(set! s/*explain-out* expound/printer)

(s/check-asserts true)

; --- COMMAND TESTS ---

(def db* (atom nil))

(defn clear-db-fixture [f]
  (reset! db* nil)
  (f))

(use-fixtures :each clear-db-fixture)

(def uuid-1 (java.util.UUID/fromString "a60f3aa2-876c-11eb-8dcd-0242ac130003"))

(def schema-registry-commands
  (merge
   (m/default-schemas)
   {:app/token :string
    :app/pong [:and
               :keyword
               [:fn #(= % :pong)]]
    :app/session [:map
                  :user/id
                  :app/token]

    :app.input/token [:map
                      [:app/token {:optional true}]]

    :app.input/register [:and
                         :app/user
                         [:map
                          :user/password2]
                         [:fn {:error/message "passwords don't match"
                               :error/path [:user/password2]}
                          (fn [{:user/keys [password password2]}]
                            (= password password2))]]

    :app.input.articles/create [:map
                                :app/article]


    :app/user [:map
               :user/email
               :user/password]

    :app/article [:map
                  :article/title]

    :article/title :string
    :user/id :uuid
    :user/email :string
    :user/password [:and
                    :string
                    [:fn {:error/message "password is too short"} #(<= 8 (count %))]]
    :user/password2 :user/password}))

(defn register [{:keys [data]}]
  (let [{:user/keys [email password]} data
        token "TOKEN-1"]
    (swap! db* #(-> %
                    (assoc-in [:users uuid-1] {:user/id uuid-1 :user/email email :user/password password})
                    (assoc-in [:tokens token] uuid-1)))
    {:user/id uuid-1
     :app/token token}))

(defn create-article [{:keys [data]}]
  (:app/article data))

(defn assoc-current-user [ctx]
  (let [token (get-in ctx [:request :data :app/token])
        user-id (get-in @db* [:tokens token])]
    (assoc-in ctx [:request :current-user] (get-in @db* [:users user-id]))))

(defn logged-in? [req]
  (-> req :current-user boolean))

(def funicular-commands
  {:api [:api
         {:interceptors [{:enter assoc-current-user}]
          :input-schema :app.input/token
          :commands {:ping {:input-schema :map
                            :output-schema :app/pong
                            :handler (fn [_] :pong)}}}
         [:session
          {:input-schema :app.input/register
           :commands {:register {:input-schema :app.input/register
                                 :output-schema :app/session
                                 :handler register}}}]
         [:articles
          {:rules logged-in?
           :commands {:create {:input-schema :app.input.articles/create
                               :output-schema :app/article
                               :handler create-article}}}]]})

(deftest ping
  (let [f (core/compile funicular-commands {:malli/registry schema-registry-commands})]
    (is (= {:command [:api/ping :pong]}
           (core/execute f {} {:command [:api/ping {}]})))))

(deftest register
  (let [f (core/compile funicular-commands {:malli/registry schema-registry-commands})]
    (is (= {:command [:api.session/register {:app/token "TOKEN-1"
                                             :user/id uuid-1}]}
           (core/execute f {} {:command [:api.session/register {:user/email "email@example.com"
                                                                :user/password "12345678"
                                                                :user/password2 "12345678"}]})))))

(deftest register-error
  (let [f (core/compile funicular-commands {:malli/registry schema-registry-commands})]
    (is (= {:command [:api.session/register {:funicular/errors {[:user/password] ["password is too short"],
                                                                [:user/password2] ["password is too short"
                                                                                   "passwords don't match"]}
                                             :funicular.anomaly/category :funicular.anomaly.category/incorrect
                                             :funicular.anomaly/message "Invalid input"
                                             :funicular.anomaly/subcategory :funicular.anomaly.category.incorrect/input-data}]}
           (core/execute f {} {:command [:api.session/register {:user/email "email@example.com"
                                                                :user/password "1234567"
                                                                :user/password2 "123456"}]})))))
(deftest interceptors-rules
  (let [f (core/compile funicular-commands {:malli/registry schema-registry-commands})
        {[_ {:app/keys [token]}] :command} (core/execute f {} {:command [:api.session/register {:user/email "email@example.com"
                                                                                                :user/password "12345678"
                                                                                                :user/password2 "12345678"}]})
        res (core/execute f {} {:command [:api.articles/create {:app/article {:article/title "My article"}
                                                                :app/token token}]})]
    (is (= {:command [:api.articles/create {:article/title "My article"}]} res))))

; --- QUERY TESTS ---

(defn repeat-letter [len ord]
  (apply str (take len (repeatedly #(char (+ ord 65))))))

(def quasi-word (partial repeat-letter 3))

(def schema-registry-queries
  (merge
   (m/default-schemas)
   {:app/seed :int
    :app/generated-numbers [:vector :int]
    :app/generated-words [:vector :string]}))

(def funicular-queries
  {:api
   [:api
    [:example
     {:queries
      {:make-numbers {:input-schema :app/seed
                      :output-schema :app/generated-numbers
                      :handler (fn [{times :data}] (vec (range times)))}
       :make-words {:input-schema :app/seed
                    :output-schema :app/generated-words
                    :handler (fn [{times :data}] (mapv quasi-word (range times)))}}}]]})

(deftest simple-query
  (let [cf (core/compile funicular-queries {:malli/registry schema-registry-queries})]
    (is (= {:queries {:return-here-pls [:api.example/make-numbers [0 1 2]]}}
           (core/execute cf {}
                         {:queries
                          {:return-here-pls
                           [:api.example/make-numbers 3]}})))))

(deftest multiple-queries
  (let [cf (core/compile funicular-queries {:malli/registry schema-registry-queries})]
    (is (= {:queries {:numbers [:api.example/make-numbers [0 1 2]]
                      :words [:api.example/make-words ["AAA" "BBB" "CCC"]]}}
           (core/execute cf {}
                         {:queries
                          {:numbers
                           [:api.example/make-numbers 3]
                           :words
                           [:api.example/make-words 3]}})))))

; --- PIPES TESTS ---

(defn merge-command-response [{:keys [command] :as request}]
  (update request :data merge (:response command)))

(def fun-word {:app/word "funicular"
               :app/definition "(of a railway, especially one on a mountainside) operating by cable with ascending and descending cars counterbalanced."
               :app/synonyms ["cableway", "wireway", "cablecar"]})

(def schema-registry-pipes
  (merge
   (m/default-schemas)
   {:app/dict-entry [:map
                     [:app/word {:optional true}]
                     :app/definition
                     :app/synonyms]
    :app/word :string
    :app/definition :string
    :app/synonyms [:vector :string]}))

(def funicular-pipes
  {:api
   [:api
    [:dictionary
     {:queries
      {:lookup-word {:input-schema :app/word
                     :output-schema :app/dict-entry
                     :handler (fn [_] fun-word)}
       :word-count {:input-schema :any
                    :output-schema :int
                    ;; read :app/word from the data returned from the command and count it. Without
                    ;; the pipe, this data wouldn't be available in the query. We increment the 
                    ;; count by the amount passed directly to the query on the `:inc-by` key
                    :handler (fn [{:keys [data]}]
                               (+ (:inc-by data) (-> data :app/word count)))}}
      :commands
      {:new-dict-entry {:input-schema :app/dict-entry
                        :output-schema :any
                        ;; return the data sent into the command with the modified word
                        :handler (fn [{:keys [data]}] (update data :app/word #(str % "!")))}}}]]
   :pipes {[:api.dictionary/new-dict-entry :api.dictionary/word-count]
           merge-command-response}})

(deftest pipes
  (let [cf (core/compile funicular-pipes {:malli/registry schema-registry-pipes})]
    (is (= {:command [:api.dictionary/new-dict-entry (assoc fun-word :app/word "funicular!")]
            :queries {:word-count [:api.dictionary/word-count 12]}}
           (core/execute cf {}
                         {:command [:api.dictionary/new-dict-entry fun-word]
                          :queries {:word-count [:api.dictionary/word-count {:inc-by 2}]}})))))

(comment
  (require '[kaocha.repl :as k])
  (k/run-all)
  (k/run 'com.verybigthings.funicular.core-test/ping)
  (k/run 'com.verybigthings.funicular.core-test/interceptors-rules)
  (k/run 'com.verybigthings.funicular.core-test/simple-query)
  (k/run 'com.verybigthings.funicular.core-test/multiple-queries)
  (k/run 'com.verybigthings.funicular.core-test/pipes))
