<#--
  Astra page layout. Same macro contract as the base theme's registrationLayout (sections: header,
  show-username, form, socialProviders, info), so every stock page template renders inside it.
-->
<#import "footer.ftl" as loginFooter>
<#import "theme-resources.ftl" as themeResourceTags>
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="robots" content="noindex, nofollow">
    <meta name="theme-color" content="#ece4d6" media="(prefers-color-scheme: light)">
    <meta name="theme-color" content="#1f2129" media="(prefers-color-scheme: dark)">

    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${title!}</title>
    <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/favicon.svg" />
    <#if themeResources?? && themeResources.stylesCommon?has_content>
        <@themeResourceTags.renderStyles themeResources.stylesCommon url.resourcesCommonPath />
    <#elseif properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if themeResources?? && themeResources.styles?has_content>
        <@themeResourceTags.renderStyles themeResources.styles url.resourcesPath />
    <#elseif properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if themeResources?? && themeResources.scripts?has_content>
        <@themeResourceTags.renderScripts themeResources.scripts url.resourcesPath "text/javascript" />
    <#elseif properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};

        startSessionPolling(
            ${url.ssoLoginInOtherTabsUrl?c}
        );
        </#outputformat>
    </script>
    <script type="module">
        document.addEventListener("click", (event) => {
            const link = event.target.closest("a[data-once-link]");
            if (!link) {
                return;
            }
            if (link.getAttribute("aria-disabled") === "true") {
                event.preventDefault();
                return;
            }
            const { disabledClass } = link.dataset;
            if (disabledClass) {
                link.classList.add(...disabledClass.trim().split(/\s+/));
            }
            link.setAttribute("role", "link");
            link.setAttribute("aria-disabled", "true");
        });
        // Show a busy state on the submit button so a slow network never invites a second tap.
        document.addEventListener("submit", (event) => {
            const button = event.target.querySelector("[type=submit]");
            if (button) {
                button.classList.add("astra-btn--busy");
            }
        });
    </script>
    <#if authenticationSession??>
        <script type="module">
            <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};

            checkAuthSession(
                ${authenticationSession.authSessionIdHash?c}
            );
            </#outputformat>
        </script>
    </#if>
</head>

<body class="${properties.kcBodyClass!} ${bodyClass}" data-page-id="login-${pageId}">
<div class="${properties.kcLoginClass!}">
    <header id="kc-header" class="${properties.kcHeaderClass!}">
        <div id="kc-header-wrapper" class="${properties.kcHeaderWrapperClass!}">
            <span class="astra-brand">
                <svg class="astra-brand__mark" viewBox="0 0 32 32" width="32" height="32" aria-hidden="true" focusable="false">
                    <circle cx="16" cy="16" r="13.25" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2.2 2.6" opacity=".5"/>
                    <path d="M16 3.5 18.6 13.4 28.5 16 18.6 18.6 16 28.5 13.4 18.6 3.5 16 13.4 13.4Z" fill="currentColor" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
                    <path class="astra-brand__core" d="M16 9.5 17.4 14.6 22.5 16 17.4 17.4 16 22.5 14.6 17.4 9.5 16 14.6 14.6Z"/>
                </svg>
                <span class="astra-brand__name">${kcSanitize(msg("loginTitleHtml",(realm.displayNameHtml!'')))?no_esc}</span>
            </span>
            <#if realm.internationalizationEnabled && locale.supported?size gt 1>
                <nav class="${properties.kcLocaleMainClass!}" id="kc-locale" aria-label="${msg("languages")}">
                    <ul class="${properties.kcLocaleListClass!}" id="language-switch1">
                        <#list locale.supported as l>
                            <#assign isCurrent = (l.languageTag!'') == (locale.currentLanguageTag!'')>
                            <li class="${properties.kcLocaleListItemClass!}">
                                <a id="language-${l?counter}" class="${properties.kcLocaleItemClass!}" href="${l.url}" lang="${l.languageTag!''}"
                                   title="${l.label}" aria-label="${l.label}" <#if isCurrent>aria-current="true"</#if>>${(l.languageTag!l.label)?upper_case}</a>
                            </li>
                        </#list>
                    </ul>
                </nav>
            </#if>
        </div>
    </header>

    <main class="${properties.kcFormCardClass!}">
        <div class="${properties.kcFormHeaderClass!}">
            <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
                <h1 id="kc-page-title"><#nested "header"></h1>
                <#if (pageId!'')?remove_ending(".ftl") == "login">
                    <p class="astra-card__lede">${msg("astraLoginLede")}</p>
                </#if>
            <#else>
                <#nested "show-username">
                <div id="kc-username" class="astra-identity">
                    <span class="astra-identity__avatar" aria-hidden="true"><#if (auth.attemptedUsername!'')?has_content>${auth.attemptedUsername?substring(0, 1)?upper_case}</#if></span>
                    <label id="kc-attempted-username" class="astra-identity__name">${auth.attemptedUsername}</label>
                    <a id="reset-login" class="astra-identity__switch" href="${url.loginRestartFlowUrl}" aria-label="${msg("restartLoginTooltip")}">
                        ${msg("astraSwitchAccount")}
                    </a>
                </div>
            </#if>
            <#if displayRequiredFields>
                <p class="astra-card__required"><span class="required" aria-hidden="true">*</span> ${msg("requiredFields")}</p>
            </#if>
        </div>

        <div id="kc-content">
            <div id="kc-content-wrapper">

                <#-- App-initiated actions should not see warning messages about the need to complete the action during login. -->
                <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                    <div class="alert-${message.type} ${properties.kcAlertClass!} astra-alert--${message.type}"
                         role="<#if message.type = 'error' || message.type = 'warning'>alert<#else>status</#if>">
                        <span class="<#if message.type = 'success'>${properties.kcFeedbackSuccessIcon!}<#elseif message.type = 'warning'>${properties.kcFeedbackWarningIcon!}<#elseif message.type = 'error'>${properties.kcFeedbackErrorIcon!}<#else>${properties.kcFeedbackInfoIcon!}</#if>" aria-hidden="true"></span>
                        <span class="${properties.kcAlertTitleClass!}">${kcSanitize(message.summary)?no_esc}</span>
                    </div>
                </#if>

                <#nested "form">

                <#if auth?has_content && auth.showTryAnotherWayLink()>
                    <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                        <div class="${properties.kcFormGroupClass!}">
                            <input type="hidden" name="tryAnotherWay" value="on"/>
                            <a href="#" id="try-another-way" class="astra-link"
                               onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                        </div>
                    </form>
                </#if>

                <#if switchOrganizationEnabled?? && switchOrganizationEnabled>
                    <form id="kc-switch-organization-form" action="${url.loginAction}" method="post">
                        <div class="${properties.kcFormGroupClass!}">
                            <input type="hidden" name="switchOrganization" value="true"/>
                            <a href="#" id="switch-organization" class="astra-link"
                               onclick="document.forms['kc-switch-organization-form'].requestSubmit();return false;">${msg("doSwitchOrganization")}</a>
                        </div>
                    </form>
                </#if>

                <#nested "socialProviders">

                <#if displayInfo>
                    <div id="kc-info" class="${properties.kcSignUpClass!}">
                        <div id="kc-info-wrapper" class="${properties.kcInfoAreaWrapperClass!}">
                            <#nested "info">
                        </div>
                    </div>
                </#if>
            </div>
        </div>
    </main>

    <@loginFooter.content/>
</div>
</body>
</html>
</#macro>
