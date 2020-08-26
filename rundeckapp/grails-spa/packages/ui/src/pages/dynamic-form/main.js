// The Vue build version to load with the `import` command
// (runtime-only or standalone) has been set in webpack.base.conf with an alias.
import Vue from 'vue'
import * as uiv from 'uiv'
import Vue2Filters from 'vue2-filters'
import VueCookies from 'vue-cookies'
import App from './App'
import VueI18n from 'vue-i18n'
import international from './i18n'
import moment from 'moment'
import uivLang from '@rundeck/ui-trellis/lib/utilities/uivi18n'

Vue.config.productionTip = false

Vue.use(Vue2Filters)
Vue.use(VueCookies)

Vue.use(uiv)
Vue.use(VueI18n)
Vue.use(VueCookies)

let messages = international.messages
let language = window._rundeck.language || 'en_US'
let locale = window._rundeck.locale || 'en_US'
let lang = window._rundeck.language || 'en'
moment.locale(locale)

// include any i18n injected in the page by the app
messages =
  {
    [locale]: Object.assign(
      {},
      uivLang[locale] || uivLang[lang] || {},
      window.Messages,
      messages[locale] || messages[lang] || messages['en_US'] || {}
    )
  }
// Create VueI18n instance with options
const i18n = new VueI18n({
  silentTranslationWarn: true,
  locale: locale, // set locale
  messages // set locale messages,

})

const els = document.body.getElementsByClassName('dynamic-form-vue')

for (var i = 0; i < els.length; i++) {
  const el = els[i];
  
  /* eslint-disable no-new */
  new Vue({
    el: el,
    render(h) {
      return h(App, {
        props: {
          fields: this.$el.attributes.fields.value,
          options: this.$el.attributes.options.value,
          hasOptions: this.$el.attributes.hasOptions.value,
          element: this.$el.attributes.element.value,
          name: this.$el.attributes.name.value,
        }
      })
    },
    i18n
  });
}
