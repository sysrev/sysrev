// -*- Mode: JavaScript; tab-width: 2; indent-tabs-mode: nil; -*-
// vim:set ft=javascript ts=2 sw=2 sts=2 cindent:

// a single EVENT type
// {
//     "borderColor": "darken",
//     "normalizations": [],
//     "name": "Life",
//     "labels": null,
//     "children": [
//         {
//             "borderColor": "darken",
//             "normalizations": [],
//             "name": "Be born",
//             "arcs": [
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Person"
//                     ],
//                     "type": "Person-Arg",
//                     "targets": [
//                         "Person"
//                     ]
//                 },
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Place"
//                     ],
//                     "type": "Place-Arg",
//                     "targets": [
//                         "GPE"
//                     ]
//                 }
//             ],
//             "labels": [
//                 "Be born"
//             ],
//             "children": [],
//             "unused": false,
//             "bgColor": "lightgreen",
//             "attributes": [
//                 "Negation",
//                 "Confidence"
//             ],
//             "type": "Be-born",
//             "fgColor": "black"
//         },
//
//
//
//         {
//             "borderColor": "darken",
//             "normalizations": [],
//             "name": "Marry",
//             "arcs": [
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Person"
//                     ],
//                     "type": "Person-Arg",
//                     "targets": [
//                         "Person"
//                     ]
//                 },
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Place"
//                     ],
//                     "type": "Place-Arg",
//                     "targets": [
//                         "GPE"
//                     ]
//                 }
//             ],
//             "labels": null,
//             "children": [],
//             "unused": false,
//             "bgColor": "lightgreen",
//             "attributes": [
//                 "Negation",
//                 "Confidence"
//             ],
//             "type": "Marry",
//             "fgColor": "black"
//         },
//         {
//             "borderColor": "darken",
//             "normalizations": [],
//             "name": "Divorce",
//             "arcs": [
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Person"
//                     ],
//                     "type": "Person-Arg",
//                     "targets": [
//                         "Person"
//                     ]
//                 },
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Place"
//                     ],
//                     "type": "Place-Arg",
//                     "targets": [
//                         "GPE"
//                     ]
//                 }
//             ],
//             "labels": null,
//             "children": [],
//             "unused": false,
//             "bgColor": "lightgreen",
//             "attributes": [
//                 "Negation",
//                 "Confidence"
//             ],
//             "type": "Divorce",
//             "fgColor": "black"
//         },
//         {
//             "borderColor": "darken",
//             "normalizations": [],
//             "name": "Die",
//             "arcs": [
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Person"
//                     ],
//                     "type": "Person-Arg",
//                     "targets": [
//                         "Person"
//                     ]
//                 },
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Agent"
//                     ],
//                     "type": "Agent-Arg",
//                     "targets": [
//                         "Person",
//                         "Organization",
//                         "GPE"
//                     ]
//                 },
//                 {
//                     "color": "black",
//                     "arrowHead": "triangle,5",
//                     "labels": [
//                         "Place"
//                     ],
//                     "type": "Place-Arg",
//                     "targets": [
//                         "GPE"
//                     ]
//                 }
//             ],
//             "labels": null,
//             "children": [],
//             "unused": false,
//             "bgColor": "lightgreen",
//             "attributes": [
//                 "Negation",
//                 "Confidence"
//             ],
//             "type": "Die",
//             "fgColor": "black"
//         }
//     ],
//     "unused": true,
//     "bgColor": "lightgreen",
//     "attributes": [
//         "Negation",
//         "Confidence"
//     ],
//     "type": "Life",
//     "fgColor": "black"
// },

var dummyReturn = {
    "protocol": 1,
    "description": null,
    "parent": null,
    "disambiguator_config": [],
    "header": [
        [
            "Document",
            "string"
        ],
        [
            "Modified",
            "time"
        ],
        [
            "Entities",
            "int"
        ],
        [
            "Relations",
            "int"
        ],
        [
            "Events",
            "int"
        ],
        [
            "Issues",
            "int"
        ]
    ],
    "entity_attribute_types": [],
    "event_types": [
        {
            "borderColor": "darken",
            "normalizations": [],
            "name": "Life",
            "labels": null,
            "children": [
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Be born",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Person"
                            ],
                            "type": "Person-Arg",
                            "targets": [
                                "Person"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Place"
                            ],
                            "type": "Place-Arg",
                            "targets": [
                                "GPE"
                            ]
                        }
                    ],
                    "labels": [
                        "Be born"
                    ],
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Be-born",
                    "fgColor": "black"
                },
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Marry",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Person"
                            ],
                            "type": "Person-Arg",
                            "targets": [
                                "Person"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Place"
                            ],
                            "type": "Place-Arg",
                            "targets": [
                                "GPE"
                            ]
                        }
                    ],
                    "labels": null,
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Marry",
                    "fgColor": "black"
                },
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Divorce",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Person"
                            ],
                            "type": "Person-Arg",
                            "targets": [
                                "Person"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Place"
                            ],
                            "type": "Place-Arg",
                            "targets": [
                                "GPE"
                            ]
                        }
                    ],
                    "labels": null,
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Divorce",
                    "fgColor": "black"
                },
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Die",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Person"
                            ],
                            "type": "Person-Arg",
                            "targets": [
                                "Person"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Agent"
                            ],
                            "type": "Agent-Arg",
                            "targets": [
                                "Person",
                                "Organization",
                                "GPE"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Place"
                            ],
                            "type": "Place-Arg",
                            "targets": [
                                "GPE"
                            ]
                        }
                    ],
                    "labels": null,
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Die",
                    "fgColor": "black"
                }
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "attributes": [
                "Negation",
                "Confidence"
            ],
            "type": "Life",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "normalizations": [],
            "name": "Transaction",
            "labels": null,
            "children": [
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Transfer ownership",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Buyer"
                            ],
                            "type": "Buyer-Arg",
                            "targets": [
                                "Person",
                                "Organization",
                                "GPE"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Seller"
                            ],
                            "type": "Seller-Arg",
                            "targets": [
                                "Person",
                                "Organization",
                                "GPE"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Artifact"
                            ],
                            "type": "Artifact-Arg",
                            "targets": [
                                "Organization"
                            ]
                        }
                    ],
                    "labels": [
                        "Transfer ownership"
                    ],
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Transfer-ownership",
                    "fgColor": "black"
                },
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Transfer money",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Giver"
                            ],
                            "type": "Giver-Arg",
                            "targets": [
                                "Person",
                                "Organization",
                                "GPE"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Recipient"
                            ],
                            "type": "Recipient-Arg",
                            "targets": [
                                "Person",
                                "Organization",
                                "GPE"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Beneficiary"
                            ],
                            "type": "Beneficiary-Arg",
                            "targets": [
                                "Person",
                                "Organization",
                                "GPE"
                            ]
                        }
                    ],
                    "labels": [
                        "Transfer money"
                    ],
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Transfer-money",
                    "fgColor": "black"
                }
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "attributes": [
                "Negation",
                "Confidence"
            ],
            "type": "Transaction",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "normalizations": [],
            "name": "Business",
            "labels": null,
            "children": [
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Start org",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Agent"
                            ],
                            "type": "Agent-Arg",
                            "targets": [
                                "Person",
                                "Organization",
                                "GPE"
                            ]
                        },
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Org"
                            ],
                            "type": "Org-Arg",
                            "targets": [
                                "Organization"
                            ]
                        }
                    ],
                    "labels": [
                        "Start org"
                    ],
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Start-org",
                    "fgColor": "black"
                },
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "Merge org",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Org"
                            ],
                            "type": "Org-Arg",
                            "targets": [
                                "Organization"
                            ]
                        }
                    ],
                    "labels": [
                        "Merge org"
                    ],
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "Merge-org",
                    "fgColor": "black"
                },
                {
                    "borderColor": "darken",
                    "normalizations": [],
                    "name": "End org",
                    "arcs": [
                        {
                            "color": "black",
                            "arrowHead": "triangle,5",
                            "labels": [
                                "Org"
                            ],
                            "type": "Org-Arg",
                            "targets": [
                                "Organization"
                            ]
                        }
                    ],
                    "labels": [
                        "End org"
                    ],
                    "children": [],
                    "unused": false,
                    "bgColor": "lightgreen",
                    "attributes": [
                        "Negation",
                        "Confidence"
                    ],
                    "type": "End-org",
                    "fgColor": "black"
                }
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "attributes": [
                "Negation",
                "Confidence"
            ],
            "type": "Business",
            "fgColor": "black"
        }
    ],
    "ui_names": {
        "entities": "entities",
        "events": "events",
        "relations": "relations",
        "attributes": "attributes"
    },
    "action": "getCollectionInformation",
    "normalization_config": [],
    "items": [
        [
            "c",
            null,
            "tutorials"
        ],
        [
            "c",
            null,
            "examples"
        ]
    ],
    "messages": [],
    "event_attribute_types": [
        {
            "unused": false,
            "values": {
                "Negation": {
                    "box": "crossed"
                }
            },
            "labels": null,
            "type": "Negation",
            "name": "Negation"
        },
        {
            "unused": false,
            "values": {
                "High": {
                    "glyph": "↑"
                },
                "Neutral": {
                    "glyph": "↔"
                },
                "Low": {
                    "glyph": "↓"
                }
            },
            "labels": null,
            "type": "Confidence",
            "name": "Confidence"
        }
    ],
    "annotation_logging": false,
    "search_config": [
        [
            "Google",
            "http://www.google.com/search?q=%s"
        ],
        [
            "Wikipedia",
            "http://en.wikipedia.org/wiki/Special:Search?search=%s"
        ]
    ],
    "ner_taggers": [],
    "relation_types": [
        {
            "args": [
                {
                    "role": "Arg1",
                    "targets": [
                        "Person"
                    ]
                },
                {
                    "role": "Arg2",
                    "targets": [
                        "GPE"
                    ]
                }
            ],
            "arrowHead": "triangle,5",
            "name": "Located",
            "color": "black",
            "labels": null,
            "children": [],
            "unused": false,
            "attributes": [],
            "type": "Located",
            "properties": {}
        },
        {
            "args": [
                {
                    "role": "Arg1",
                    "targets": [
                        "GPE"
                    ]
                },
                {
                    "role": "Arg2",
                    "targets": [
                        "GPE"
                    ]
                }
            ],
            "arrowHead": "triangle,5",
            "name": "Geographical_part",
            "color": "black",
            "labels": null,
            "children": [],
            "unused": false,
            "attributes": [],
            "type": "Geographical_part",
            "properties": {}
        },
        {
            "args": [
                {
                    "role": "Arg1",
                    "targets": [
                        "Person"
                    ]
                },
                {
                    "role": "Arg2",
                    "targets": [
                        "Person"
                    ]
                }
            ],
            "arrowHead": "triangle,5",
            "name": "Family",
            "color": "black",
            "labels": null,
            "children": [],
            "unused": false,
            "attributes": [],
            "type": "Family",
            "properties": {}
        },
        {
            "args": [
                {
                    "role": "Arg1",
                    "targets": [
                        "Person"
                    ]
                },
                {
                    "role": "Arg2",
                    "targets": [
                        "GPE"
                    ]
                }
            ],
            "arrowHead": "triangle,5",
            "name": "Employment",
            "color": "black",
            "labels": null,
            "children": [],
            "unused": false,
            "attributes": [],
            "type": "Employment",
            "properties": {}
        },
        {
            "args": [
                {
                    "role": "Arg1",
                    "targets": [
                        "Person"
                    ]
                },
                {
                    "role": "Arg2",
                    "targets": [
                        "Organization"
                    ]
                }
            ],
            "arrowHead": "triangle,5",
            "name": "Ownership",
            "color": "black",
            "labels": null,
            "children": [],
            "unused": false,
            "attributes": [],
            "type": "Ownership",
            "properties": {}
        },
        {
            "args": [
                {
                    "role": "Arg1",
                    "targets": [
                        "Organization"
                    ]
                },
                {
                    "role": "Arg2",
                    "targets": [
                        "GPE"
                    ]
                }
            ],
            "arrowHead": "triangle,5",
            "name": "Origin",
            "color": "black",
            "labels": null,
            "children": [],
            "unused": false,
            "attributes": [],
            "type": "Origin",
            "properties": {}
        },
        {
            "args": [
                {
                    "role": "Arg1",
                    "targets": [
                        "Person"
                    ]
                },
                {
                    "role": "Arg2",
                    "targets": [
                        "Person"
                    ]
                }
            ],
            "arrowHead": "none",
            "name": "Alias",
            "color": "black",
            "labels": null,
            "children": [],
            "unused": false,
            "dashArray": "3,3",
            "attributes": [],
            "type": "Alias",
            "properties": {
                "symmetric": true,
                "transitive": true
            }
        }
    ],
    "entity_types": [
        {
            "borderColor": "darken",
            "normalizations": [],
            "name": "Person",
            "arcs": [
                {
                    "color": "black",
                    "arrowHead": "triangle,5",
                    "labels": [
                        "Located"
                    ],
                    "type": "Located",
                    "targets": [
                        "GPE"
                    ]
                },
                {
                    "color": "black",
                    "arrowHead": "triangle,5",
                    "labels": [
                        "Family"
                    ],
                    "type": "Family",
                    "targets": [
                        "Person"
                    ]
                },
                {
                    "color": "black",
                    "arrowHead": "triangle,5",
                    "labels": [
                        "Employment"
                    ],
                    "type": "Employment",
                    "targets": [
                        "GPE"
                    ]
                },
                {
                    "color": "black",
                    "arrowHead": "triangle,5",
                    "labels": [
                        "Ownership"
                    ],
                    "type": "Ownership",
                    "targets": [
                        "Organization"
                    ]
                },
                {
                    "arrowHead": "none",
                    "color": "black",
                    "labels": [
                        "Alias"
                    ],
                    "dashArray": "3,3",
                    "type": "Alias",
                    "targets": [
                        "Person"
                    ]
                }
            ],
            "labels": [
                "Person"
            ],
            "children": [],
            "unused": false,
            "bgColor": "#ffccaa",
            "attributes": [],
            "type": "Person",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "normalizations": [],
            "name": "Organization",
            "arcs": [
                {
                    "color": "black",
                    "arrowHead": "triangle,5",
                    "labels": [
                        "Origin"
                    ],
                    "type": "Origin",
                    "targets": [
                        "GPE"
                    ]
                }
            ],
            "labels": [
                "Organization",
                "Org"
            ],
            "children": [],
            "unused": false,
            "bgColor": "#8fb2ff",
            "attributes": [],
            "type": "Organization",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "normalizations": [],
            "name": "Geo-political entity",
            "arcs": [
                {
                    "color": "black",
                    "arrowHead": "triangle,5",
                    "labels": [
                        "Geographical_part"
                    ],
                    "type": "Geographical_part",
                    "targets": [
                        "GPE"
                    ]
                }
            ],
            "labels": [
                "Geo-political entity",
                "GPE"
            ],
            "children": [],
            "unused": false,
            "bgColor": "#7fe2ff",
            "attributes": [],
            "type": "GPE",
            "fgColor": "black"
        }
    ],
    "relation_attribute_types": []
}

var whoamiResponse = {
    "action": "whoami",
    "messages": [],
    "protocol": 1,
    "user": null
}

// action: getDocument
// collection: /tutorials/tutorials/news/
// document: 030-login

var Ajax = (function ($, window, undefined) {
    var Ajax = function (dispatcher) {

        var that = this;
        var pending = 0;
        var count = 0;
        var pendingList = {};

        // merge data will get merged into the response data
        // before calling the callback
        var ajaxCall = function (data, callback, merge, extraOptions) {
            // if (data.action === "getCollectionInformation") {
            //     dispatcher.post('messages', [dummyReturn.messages]);
            //     dispatcher.post(0, callback, [dummyReturn]);
            //     return dummyReturn;
            // }
            if (data.action === "whoami") {
                // dont show the tut
                dispatcher.post('messages', [whoamiResponse.messages]);
                dispatcher.post(0, callback, [whoamiResponse]);
                return whoamiResponse;
            }

            merge = merge || {};
            dispatcher.post('spin');
            pending++;
            var id = count++;

            // special value: `merge.keep = true` prevents obsolescence
            pendingList[id] = merge.keep || false;
            delete merge.keep;

            // If no protocol version is explicitly set, set it to current
            if (data['protocol'] === undefined) {
                // TODO: Extract the protocol version somewhere global
                data['protocol'] = 1;
            }

            options = {
                data: data,
                success: function (response) {
                    pending--;
                    // If no exception is set, verify the server results
                    if (response.exception == undefined && response.action !== data.action) {
                        console.error('Action ' + data.action +
                            ' returned the results of action ' + response.action);
                        response.exception = true;
                        dispatcher.post('messages', [[['Protocol error: Action' + data.action + ' returned the results of action ' + response.action + ' maybe the server is unable to run, please run tools/troubleshooting.sh from your installation to diagnose it', 'error', -1]]]);
                    }

                    // If the request is obsolete, do nothing; if not...
                    if (pendingList.hasOwnProperty(id)) {
                        dispatcher.post('messages', [response.messages]);
                        if (response.exception == 'configurationError'
                            || response.exception == 'protocolVersionMismatch') {
                            // this is a no-rescue critical failure.
                            // Stop *everything*.
                            pendingList = {};
                            dispatcher.post('screamingHalt');
                            // If we had a protocol mismatch, prompt the user for a reload
                            if (response.exception == 'protocolVersionMismatch') {
                                if (confirm('The server is running a different version ' +
                                    'from brat than your client, possibly due to a ' +
                                    'server upgrade. Would you like to reload the ' +
                                    'current page to update your client to the latest ' +
                                    'version?')) {
                                    window.location.reload(true);
                                } else {
                                    dispatcher.post('messages', [[['Fatal Error: Protocol ' +
                                        'version mismatch, please contact the administrator',
                                        'error', -1]]]);
                                }
                            }
                            return;
                        }

                        delete pendingList[id];

                        // if .exception is just Boolean true, do not process
                        // the callback; if it is anything else, the
                        // callback is responsible for handling it
                        if (response.exception == true) {
                            $('#waiter').dialog('close');
                        } else if (callback) {
                            $.extend(response, merge);
                            dispatcher.post(0, callback, [response]);
                        }
                    }
                    dispatcher.post('unspin');
                },
                error: function (response, textStatus, errorThrown) {
                    pending--;
                    dispatcher.post('unspin');
                    $('#waiter').dialog('close');
                    dispatcher.post('messages', [[['Error: Action' + data.action + ' failed on error ' + response.statusText, 'error']]]);
                    console.error(textStatus + ':', errorThrown, response);
                }
            };

            if (extraOptions) {
                $.extend(options, extraOptions);
            }

            // prevents quickly happening events for overriding the previous val
            // before the below setTimeout has a chance to fire.
            var optionsRef = options;

            setTimeout(() =>
                window.ajaxCallback(optionsRef),
                0
            );
            return id;
        };

        var isReloadOkay = function () {
            // do not reload while data is pending
            return pending == 0;
        };

        var makeObsolete = function (all) {
            if (all) {
                pendingList = {};
            } else {
                $.each(pendingList, function (id, keep) {
                    if (!keep) delete pendingList[id];
                });
            }
        }

        dispatcher.
            on('isReloadOkay', isReloadOkay).
            on('makeAjaxObsolete', makeObsolete).
            on('ajax', ajaxCall);
    };

    return Ajax;
})(jQuery, window);
