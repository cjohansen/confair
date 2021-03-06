(ns confair.config-admin-test
  (:require [clojure.test :refer [deftest is testing]]
            [confair.config-admin :as sut]
            [confair.config :as config]
            [test-with-files.core :refer [tmp-dir with-files]]))

(deftest conceal-reveal-test
  (with-files [["/config.edn" (str
                               "^" {:config/secrets {:secret/test "mypass"}}
                               {:api-key "foobar"})]]
    (let [config-path (str tmp-dir "/config.edn")]

      ;; conceal

      (is (= (sut/conceal-value (config/from-file config-path)
                                :secret/test
                                :api-key)
             [:concealed :api-key :in config-path]))

      (let [config (config/from-file config-path)]
        (is (= (:api-key config) "foobar"))
        (is (= (:config/encrypted-keys (meta config)) {:api-key :secret/test})))

      (is (re-find #":api-key \[:secret/test " (slurp config-path)))

      ;; reveal

      (is (= (sut/reveal-value (config/from-file config-path)
                               :api-key)
             [:revealed :api-key :in config-path]))

      (let [config (config/from-file config-path)]
        (is (= (:api-key config) "foobar"))
        (is (= (:config/encrypted-keys (meta config)) {})))

      (is (re-find #":api-key \"foobar\"" (slurp config-path))))))

(deftest replace-secret-test
  (with-files [["/foo.edn" (str {:api-key "ghosts"})]
               ["/bar.edn" (str {:password "goblins"})]
               ["/baz.edn" (str {:theme "ghouls"})]]
    (is (= (sut/conceal-value (config/from-file (str tmp-dir "/foo.edn")
                                                {:secret/test "boom"})
                              :secret/test
                              :api-key)
           [:concealed :api-key :in (str tmp-dir "/foo.edn")]))
    (is (= (sut/conceal-value (config/from-file (str tmp-dir "/bar.edn")
                                                {:secret/test "boom"})
                              :secret/test
                              :password)
           [:concealed :password :in (str tmp-dir "/bar.edn")]))

    (is (= (set
            (sut/replace-secret {:files (sut/find-files tmp-dir #"edn$")
                                 :secret-key :secret/test
                                 :old-secret "boom"
                                 :new-secret "bang"}))
           #{[:replaced-secret :api-key :in (str tmp-dir "/foo.edn")]
             [:replaced-secret :password :in (str tmp-dir "/bar.edn")]
             [:nothing-to-do :in (str tmp-dir "/baz.edn")]}))

    (spit (str tmp-dir "/merged.edn")
          (str "^" {:config/secrets {:secret/test "bang"}
                    :dev-config/import [(str tmp-dir "/foo.edn")
                                        (str tmp-dir "/bar.edn")
                                        (str tmp-dir "/baz.edn")]}
               {:merged? true}))

    (let [config (config/from-file (str tmp-dir "/merged.edn"))]
      (is (= config {:api-key "ghosts"
                     :password "goblins"
                     :theme "ghouls"
                     :merged? true})))))
