
# Table of Contents

1.  [High Priority](#orga5b0d07)
    1.  [UI for transferring projects](#orga37722d)
    2.  [Log user activity (similar to Google Analytics) to local database](#org38a648f)
    3.  [Run migration for assigning owner on existing projects](#org87b2250)
2.  [Major Tasks](#org61c60d9)
    1.  [Add support for article types](#org8ba2dc7)
        1.  [Support basic JSON type for article content](#orge2eea7f)
    2.  [Add documentation links](#org80302cc)
        1.  [Create standard component for documentation links](#orga5a042f)
        2.  [Add links to GitHub wiki throughout app](#org082b4c8)
    3.  [Improve and document review label keyboard hotkeys](#orgf37584f)
        1.  [Add large "Keyboard Hotkeys" tooltip on review Labels interface](#org181f6cd)
        2.  [Set focus to first review label upon loading interface or article](#org1f7207d)
        3.  [Add hotkey for saving review labels (Ctrl+Enter?)](#org5f1c227)
        4.  [Add Up/Down hotkeys for navigating between labels](#org538a39b)
    4.  [Google OAuth registration/login](#orge599d46)
        1.  [Allow creating account via OAuth](#org9ac05ba)
        2.  [Use current url hostname for oauth redirect (not localhost)](#org7aced3e)
    5.  [User/Org URLs](#org687d468)
    6.  [Fix SEO issues](#org1672f4a)
        1.  [Update robots.txt/sitemap.xml to block indexing all unimportant URLs](#org2fbcc98)
        2.  [Change window title to reflect current page + context (project, user, org, etc)](#orgeceaa30)
        3.  [Return project description text in HTML meta element (project overview)](#org02caee1)
    7.  [Add support for nested labels](#orgf2b7a46)
3.  [Fixes](#org8190765)
    1.  [(Annotator) New sidebar item can be activated incorrectly (?)](#org67d2624)
    2.  [Sidebar only when editing article (?)](#org68f92c7)
    3.  [Show an "Access Denied"-type message for PDFs instead of silently hiding](#orgbdaf61c)
    4.  [Fix clone-project issues](#org47f82de)
        1.  [Copy sources along with articles](#org5b6acd6)
        2.  [Copy "Project Documents" entries](#org5663d68)
4.  [Improvements](#orgfed0e72)
    1.  [Proper rendering of error messages on article import](#org86a44d0)
        1.  [Add test for display of these messages](#org1b8537f)
    2.  [Allow removing members from project settings](#org91e5c2b)
    3.  [Allow dev users to delete account (user settings page)](#orgce68008)
    4.  [Allow deleting projects with small number of labeled articles](#org3d894aa)
5.  [Code Maintainence](#org869cd4e)
    1.  [Add on-{success,error,response} hooks to {def-data,def-action}](#org7b0d3c2)
    2.  [Rewrite remaining AJAX calls to use {def-data,def-action}](#org8160cae)
    3.  [Remove panel navigation subpanel logic (:navigate, load-default-panels)](#org6283748)
    4.  [Store error message/response automatically from {def-data,def-action}](#org6b95baa)
    5.  [Remove routes.cljs, move sr-defroute forms to panel namespaces](#org9ace83e)
    6.  [Improve join syntax for q/find q/modify](#org8504094)
    7.  [Determine join field automatically in calls to q/find q/modify](#org6a23258)
    8.  [Add more SQL clauses in q/find etc](#org0dc3c19)
    9.  [Remove user default-project-id everywhere](#org106163a)
        1.  [Remove update-user-default-project function](#org3404c6a)
        2.  [Remove default-project-id from db](#org670a0a2)
    10. [Add general mechanism for requiring login on a page](#orgb4e635b)
6.  [Test suite](#orgafde0fa)
    1.  [Review all web interfaces for test coverage](#org9f815a1)
    2.  [Invite link flow (register, login, join, already member)](#org1621973)
    3.  [All non-authenticated pages work (logged out)](#orgd9e7b6a)
    4.  [All authenticated pages require login (logged out)](#org4bdf536)
    5.  [Public review pages (logged out)](#orgbeeb35b)
    6.  [Public review pages (non-member)](#orgb36421f)
    7.  [Full testing for labels/annotations editor state](#org718c99d)
    8.  [Update test suite to use only ordinary (non-dev) accounts in tests](#orga285fba)
7.  [Labels](#orgbdf9077)
    1.  [Support changing/removing values in categorical label definitions](#orgfaaa1f9)
    2.  [Add label editor field for string label regex requirement](#orga282406)
    3.  [Add keyword editor UI (within label definitions UI)](#orge7d2a2c)
        1.  [Merge rendering for text annotations and keyword highlights](#org43294fe)
8.  [Design](#orgb8a9444)
    1.  [Add more info to project listing element (# articles, labels, users)](#orgc24eac5)
    2.  [Change label popups to use FixedTooltipElement (better size/position)](#orgf6e959f)
    3.  [Redesign /user/<user>/profile default page](#org4e172b9)
        1.  [Place useful content (projects/orgs) on main page](#orgfcbe836)
        2.  [Change URL to /user/<user>](#org70e9eae)
        3.  [Disable "Projects" and "Orgs" tabs on User pages when empty](#org2d28e32)
    4.  [Redesign UI for "Public Reviewer Opt In"](#org0a45ce4)
    5.  [Redesign UI for "Invite this user to <Select Project>"](#orgcbf6d10)
    6.  [Redesign global UI styling](#org61a60e6)
9.  [Mobile](#orgdcb9a4e)
    1.  [Use nav-scroll-top for article links from article list](#orga7d58c4)
    2.  [Change /user/\*/profile layout, reduce size of user image](#orge948e51)
    3.  [Change /user/\*/projects layout, text generally too large](#orgeddd792)
    4.  [Improve layout for Billing pages](#org8a2b383)
    5.  [Fix layout for Org Members list](#org39eaa93)
    6.  ["Add Member" modal doesn't fit on screen](#org4db6eba)
    7.  [Popup for "Sources" on article page breaks page position](#org1b54a22)
10. [New functionality (low priority)](#org380348f)
    1.  [Add web interface for cloning projects](#org6d2bcc9)
    2.  [Support copying label definitions from other projects](#orgb2ca806)
11. [(Misc) SDS Requests](#org7abf84d)
    1.  [Suggested: Option for UserAnswers, multiple rows per article](#org3d5b1e2)
        1.  [User sets a label or labels to include with article-id as key value](#org0e280a2)
        2.  [One row per combination of article-id and answer value (/p/4047 export by tuples of [article,chemical])](#orgd4eafca)


<a id="orga5b0d07"></a>

# High Priority


<a id="orga37722d"></a>

## TODO UI for transferring projects


<a id="org38a648f"></a>

## TODO Log user activity (similar to Google Analytics) to local database


<a id="org87b2250"></a>

## TODO Run migration for assigning owner on existing projects


<a id="org61c60d9"></a>

# Major Tasks


<a id="org8ba2dc7"></a>

## TODO Add support for article types


<a id="orge2eea7f"></a>

### TODO Support basic JSON type for article content


<a id="org80302cc"></a>

## TODO Add documentation links


<a id="orga5a042f"></a>

### TODO Create standard component for documentation links


<a id="org082b4c8"></a>

### TODO Add links to GitHub wiki throughout app


<a id="orgf37584f"></a>

## TODO Improve and document review label keyboard hotkeys


<a id="org181f6cd"></a>

### TODO Add large "Keyboard Hotkeys" tooltip on review Labels interface


<a id="org1f7207d"></a>

### TODO Set focus to first review label upon loading interface or article


<a id="org5f1c227"></a>

### TODO Add hotkey for saving review labels (Ctrl+Enter?)


<a id="org538a39b"></a>

### TODO Add Up/Down hotkeys for navigating between labels


<a id="orge599d46"></a>

## TODO Google OAuth registration/login


<a id="org9ac05ba"></a>

### TODO Allow creating account via OAuth


<a id="org7aced3e"></a>

### TODO Use current url hostname for oauth redirect (not localhost)


<a id="org687d468"></a>

## TODO User/Org URLs

Use :set-project-url-error for project id lookup errors (?)


<a id="org1672f4a"></a>

## TODO Fix SEO issues


<a id="org2fbcc98"></a>

### TODO Update robots.txt/sitemap.xml to block indexing all unimportant URLs


<a id="orgeceaa30"></a>

### TODO Change window title to reflect current page + context (project, user, org, etc)


<a id="org02caee1"></a>

### TODO Return project description text in HTML meta element (project overview)


<a id="orgf2b7a46"></a>

## TODO Add support for nested labels


<a id="org8190765"></a>

# Fixes


<a id="org67d2624"></a>

## TODO (Annotator) New sidebar item can be activated incorrectly (?)

If user is already editing an annotation ("Save" button shown),
selecting new text will create a new entry but will not activate it,
while the original entry remains active.


<a id="org68f92c7"></a>

## TODO Sidebar only when editing article (?)


<a id="orgbdaf61c"></a>

## TODO Show an "Access Denied"-type message for PDFs instead of silently hiding


<a id="org47f82de"></a>

## TODO Fix clone-project issues


<a id="org5b6acd6"></a>

### TODO Copy sources along with articles


<a id="org5663d68"></a>

### TODO Copy "Project Documents" entries


<a id="orgfed0e72"></a>

# Improvements


<a id="org86a44d0"></a>

## TODO Proper rendering of error messages on article import


<a id="org1b8537f"></a>

### TODO Add test for display of these messages


<a id="org91e5c2b"></a>

## TODO Allow removing members from project settings


<a id="orgce68008"></a>

## TODO Allow dev users to delete account (user settings page)


<a id="org3d894aa"></a>

## TODO Allow deleting projects with small number of labeled articles


<a id="org869cd4e"></a>

# Code Maintainence


<a id="org7b0d3c2"></a>

## TODO Add on-{success,error,response} hooks to {def-data,def-action}


<a id="org8160cae"></a>

## TODO Rewrite remaining AJAX calls to use {def-data,def-action}


<a id="org6283748"></a>

## TODO Remove panel navigation subpanel logic (:navigate, load-default-panels)


<a id="org6b95baa"></a>

## TODO Store error message/response automatically from {def-data,def-action}


<a id="org9ace83e"></a>

## TODO Remove routes.cljs, move sr-defroute forms to panel namespaces


<a id="org8504094"></a>

## TODO Improve join syntax for q/find q/modify


<a id="org6a23258"></a>

## TODO Determine join field automatically in calls to q/find q/modify


<a id="org0dc3c19"></a>

## TODO Add more SQL clauses in q/find etc

order-by, group, &#x2026;


<a id="org106163a"></a>

## TODO Remove user default-project-id everywhere


<a id="org3404c6a"></a>

### TODO Remove update-user-default-project function


<a id="org670a0a2"></a>

### TODO Remove default-project-id from db


<a id="orgb4e635b"></a>

## TODO Add general mechanism for requiring login on a page


<a id="orgafde0fa"></a>

# Test suite


<a id="org9f815a1"></a>

## TODO Review all web interfaces for test coverage


<a id="org1621973"></a>

## TODO Invite link flow (register, login, join, already member)

Should replace with better system; needs tests until then.


<a id="orgd9e7b6a"></a>

## TODO All non-authenticated pages work (logged out)


<a id="org4bdf536"></a>

## TODO All authenticated pages require login (logged out)


<a id="orgbeeb35b"></a>

## TODO Public review pages (logged out)


<a id="orgb36421f"></a>

## TODO Public review pages (non-member)


<a id="org718c99d"></a>

## TODO Full testing for labels/annotations editor state


<a id="orga285fba"></a>

## DONE Update test suite to use only ordinary (non-dev) accounts in tests


<a id="orgbdf9077"></a>

# Labels


<a id="orgfaaa1f9"></a>

## TODO Support changing/removing values in categorical label definitions


<a id="orga282406"></a>

## TODO Add label editor field for string label regex requirement


<a id="orge7d2a2c"></a>

## TODO Add keyword editor UI (within label definitions UI)


<a id="org43294fe"></a>

### TODO Merge rendering for text annotations and keyword highlights


<a id="orgb8a9444"></a>

# Design


<a id="orgc24eac5"></a>

## TODO Add more info to project listing element (# articles, labels, users)


<a id="orgf6e959f"></a>

## TODO Change label popups to use FixedTooltipElement (better size/position)


<a id="org4e172b9"></a>

## TODO Redesign /user/<user>/profile default page


<a id="orgfcbe836"></a>

### TODO Place useful content (projects/orgs) on main page


<a id="org70e9eae"></a>

### TODO Change URL to /user/<user>


<a id="org2d28e32"></a>

### TODO Disable "Projects" and "Orgs" tabs on User pages when empty


<a id="org0a45ce4"></a>

## TODO Redesign UI for "Public Reviewer Opt In"


<a id="orgcbf6d10"></a>

## TODO Redesign UI for "Invite this user to <Select Project>"


<a id="org61a60e6"></a>

## TODO Redesign global UI styling

More flat / modern; fewer bordered rectangles.


<a id="orgdcb9a4e"></a>

# Mobile


<a id="orga7d58c4"></a>

## TODO Use nav-scroll-top for article links from article list


<a id="orge948e51"></a>

## TODO Change /user/\*/profile layout, reduce size of user image


<a id="orgeddd792"></a>

## TODO Change /user/\*/projects layout, text generally too large


<a id="org8a2b383"></a>

## TODO Improve layout for Billing pages


<a id="org39eaa93"></a>

## TODO Fix layout for Org Members list


<a id="org4db6eba"></a>

## TODO "Add Member" modal doesn't fit on screen


<a id="org1b54a22"></a>

## TODO Popup for "Sources" on article page breaks page position


<a id="org380348f"></a>

# New functionality (low priority)


<a id="org6d2bcc9"></a>

## TODO Add web interface for cloning projects


<a id="orgb2ca806"></a>

## TODO Support copying label definitions from other projects


<a id="org7abf84d"></a>

# (Misc) SDS Requests


<a id="org3d5b1e2"></a>

## Suggested: Option for UserAnswers, multiple rows per article


<a id="org0e280a2"></a>

### User sets a label or labels to include with article-id as key value


<a id="orgd4eafca"></a>

### One row per combination of article-id and answer value (/p/4047 export by tuples of [article,chemical])

