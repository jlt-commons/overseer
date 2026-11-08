(ns overseer.plugin
  "The plugin contract. A plugin is a Jolt namespace (or set of them) in
  ~/.config/overseer/plugins/<plugin>/ that, on load, registers sensors,
  tools and agents. Plugins require this namespace and call:

    (overseer.plugin/register-sensor!   {:id :foo :kind :text :capture ...})
    (overseer.plugin/register-tool!     {:name \"foo\" :fn ...})
    (overseer.plugin/register-agent!    {:id :bar :prompt ... :code ...})

  Registration writes to the process-wide registries, which `overseer`
  assembles into the running system at startup."
  (:require [overseer.agent :as agent]
            [overseer.sensor :as sensor]
            [overseer.tool :as tool]))

(def register-sensor! sensor/register!)
(def unregister-sensor! sensor/unregister!)
(def register-tool! tool/register!)
(def unregister-tool! tool/unregister!)
(def register-agent! agent/register-agent!)
(def unregister-agent! agent/unregister-agent!)
