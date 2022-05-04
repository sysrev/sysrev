// -*- Mode: JavaScript; tab-width: 2; indent-tabs-mode: nil; -*-
// vim:set ft=javascript ts=2 sw=2 sts=2 cindent:

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
    "unconfigured_types": [
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein family or group",
            "color": "black",
            "labels": [
                "Protein family or group",
                "PFM"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Protein_family_or_group",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Chemical",
            "color": "black",
            "labels": [
                "Chemical",
                "Chem"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Chemical",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Theme",
            "color": "black",
            "labels": [
                "Theme",
                "Th"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Theme",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Palmitoylation",
            "color": "black",
            "labels": [
                "Palmitoylation",
                "Palm"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Palmitoylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Agent",
            "color": "black",
            "labels": [
                "Agent"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Agent-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Subunit-Complex",
            "color": "black",
            "labels": [
                "Subunit-Complex",
                "Complex",
                "Cmplx"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Subunit-Complex",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Planned process",
            "color": "black",
            "labels": [
                "Planned process",
                "Planned"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Planned_process",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Delipidation",
            "color": "black",
            "labels": [
                "Delipidation",
                "-Lipid"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Delipidation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Positive regulation",
            "color": "black",
            "labels": [
                "Positive regulation",
                "+Regulation",
                "+Reg"
            ],
            "unused": true,
            "bgColor": "#e0ff00",
            "type": "Positive_regulation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Buyer",
            "color": "black",
            "labels": [
                "Buyer"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Buyer-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Sumoylation",
            "color": "black",
            "labels": [
                "Sumoylation",
                "Sumo"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Sumoylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organ",
            "color": "black",
            "labels": [
                "Organ",
                "Ogn"
            ],
            "unused": true,
            "bgColor": "#e999ff",
            "type": "Organ",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Experimental Technique",
            "color": "black",
            "labels": [
                "Experimental Technique",
                "Exp Tech"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Experimental_Technique",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Regulon-operon",
            "color": "black",
            "labels": [
                "Regulon-operon",
                "Reg/op"
            ],
            "unused": true,
            "bgColor": "#9999ff",
            "type": "Regulon-operon",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Phosphorylation",
            "color": "black",
            "labels": [
                "Phosphorylation",
                "Phos"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Phosphorylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Recipient",
            "color": "black",
            "labels": [
                "Recipient"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Recipient-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deglycosylation",
            "color": "black",
            "labels": [
                "Deglycosylation",
                "-Glyc"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deglycosylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Point Mutation",
            "color": "black",
            "labels": [
                "Point Mutation"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Point_Mutation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Dephosphorylation",
            "color": "black",
            "labels": [
                "Dephosphorylation",
                "-Phos"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Dephosphorylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deacylation",
            "color": "black",
            "labels": [
                "Deacylation",
                "-Acyl"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deacylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organism",
            "color": "black",
            "labels": [
                "Organism",
                "Org"
            ],
            "unused": true,
            "bgColor": "#ffccaa",
            "type": "Organism",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Sidechain",
            "color": "#303030",
            "labels": [
                "Sidechain",
                "SCh"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Sidechain",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Binding",
            "color": "black",
            "labels": [
                "Binding",
                "Bind"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Binding",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Entity",
            "color": "black",
            "labels": [
                "Entity",
                "Ent",
                "En",
                "E"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "Entity",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Subcellular structure",
            "color": "black",
            "labels": [
                "Subcellular structure",
                "Subcell struct",
                "SubCell",
                "SCell",
                "SC"
            ],
            "unused": true,
            "bgColor": "#bbc3ff",
            "type": "Subcellular_structure",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein",
            "color": "black",
            "labels": [
                "Protein",
                "Pro",
                "Pr",
                "P"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Protein",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Negative regulation",
            "color": "black",
            "labels": [
                "Negative regulation",
                "-Regulation",
                "-Reg"
            ],
            "unused": true,
            "bgColor": "#ffe000",
            "type": "Negative_regulation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Participant",
            "color": "black",
            "labels": [
                "Participant",
                "Pa"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Participant",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Glycosylation",
            "color": "black",
            "labels": [
                "Glycosylation",
                "Glyc"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Glycosylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Transcription",
            "color": "black",
            "labels": [
                "Transcription",
                "Trns"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Transcription",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organism subdivision",
            "color": "black",
            "labels": [
                "Organism subdivision",
                "Org subdiv",
                "OSubdiv",
                "OSD"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Organism_subdivision",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organism substance",
            "color": "black",
            "labels": [
                "Organism substance",
                "Ogn subst",
                "OSubst",
                "OSub",
                "OS"
            ],
            "unused": true,
            "bgColor": "#ffeee0",
            "type": "Organism_substance",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Catalysis",
            "color": "black",
            "labels": [
                "Catalysis",
                "Catal"
            ],
            "unused": true,
            "bgColor": "#e0ff00",
            "type": "Catalysis",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Alkylation",
            "color": "black",
            "labels": [
                "Alkylation",
                "Alkyl"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Alkylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein molecule",
            "color": "black",
            "labels": [
                "Protein molecule",
                "Prot.mol"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Protein_molecule",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Desumoylation",
            "color": "black",
            "labels": [
                "Desumoylation",
                "-Sumo"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Desumoylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Cell_type",
            "color": "black",
            "labels": [
                "Cell_type",
                "Cell_t",
                "Cell"
            ],
            "unused": true,
            "bgColor": "#cf9fff",
            "type": "Cell_type",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Multicellular_organism_natural",
            "color": "black",
            "labels": [
                "Multicellular_organism_natural",
                "M-C Organism",
                "MCOrg"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Multicellular_organism_natural",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Biological process",
            "color": "black",
            "labels": [
                "Biological process",
                "Biol proc"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Biological_process",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Gene Expression",
            "color": "black",
            "labels": [
                "Gene Expression",
                "Expression"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Gene_Expression",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Core Angiogenesis Term",
            "color": "black",
            "labels": [
                "Core Angiogenesis Term",
                "Core Angiogenesis",
                "Core Angio"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Core_Angiogenesis_Term",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Carbohydrate",
            "color": "black",
            "labels": [
                "Carbohydrate",
                "Carb"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Carbohydrate",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deacetylation",
            "color": "black",
            "labels": [
                "Deacetylation",
                "-Acet"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deacetylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Money",
            "color": "black",
            "labels": [
                "Money"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Money-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Pathological formation",
            "color": "black",
            "labels": [
                "Pathological formation",
                "Pathological form",
                "Path form",
                "Path f",
                "PF"
            ],
            "unused": true,
            "bgColor": "#aaaaaa",
            "type": "Pathological_formation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deprenylation",
            "color": "black",
            "labels": [
                "Deprenylation",
                "-Prenyl"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deprenylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Artifact",
            "color": "black",
            "labels": [
                "Artifact"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Artifact-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Person",
            "color": "black",
            "labels": [
                "Person"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Person-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Localization",
            "color": "black",
            "labels": [
                "Localization",
                "Locl"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Localization",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Laboratory Technique",
            "color": "black",
            "labels": [
                "Laboratory Technique",
                "Lab Tech"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Laboratory_Technique",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Hydroxylation",
            "color": "black",
            "labels": [
                "Hydroxylation",
                "Hydr"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Hydroxylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Seller",
            "color": "black",
            "labels": [
                "Seller"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Seller-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Cellular physiological process",
            "color": "black",
            "labels": [
                "Cellular physiological process",
                "Cell phys proc"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Cellular_physiological_process",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Drug or compound",
            "color": "black",
            "labels": [
                "Drug or compound",
                "Drug/comp",
                "D/C"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Drug_or_compound",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein catabolism",
            "color": "black",
            "labels": [
                "Protein catabolism",
                "Catabolism",
                "Catab"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Protein_catabolism",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Lipidation",
            "color": "black",
            "labels": [
                "Lipidation",
                "Lipid"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Lipidation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Acetylation",
            "color": "black",
            "labels": [
                "Acetylation",
                "Acet"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Acetylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Prenylation",
            "color": "black",
            "labels": [
                "Prenylation",
                "Prenyl"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Prenylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Demethylation",
            "color": "black",
            "labels": [
                "Demethylation",
                "-Meth"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Demethylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "DNA demethylation",
            "color": "black",
            "labels": [
                "DNA demethylation",
                "DNA -meth"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "DNA_demethylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Gene Activation",
            "color": "black",
            "labels": [
                "Gene Activation",
                "Activation"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Gene_Activation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Gene Repression",
            "color": "black",
            "labels": [
                "Gene Repression",
                "Repression"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Gene_Repression",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deneddylation",
            "color": "black",
            "labels": [
                "Deneddylation",
                "-Nedd"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deneddylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "DNA domain or region",
            "color": "black",
            "labels": [
                "DNA domain or region",
                "DDR"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "DNA_domain_or_region",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Dehydroxylation",
            "color": "black",
            "labels": [
                "Dehydroxylation",
                "-Hydr"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Dehydroxylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Dealkylation",
            "color": "black",
            "labels": [
                "Dealkylation",
                "-Alkyl"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Dealkylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Contextgene",
            "color": "#303030",
            "labels": [
                "Contextgene",
                "CGn"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Contextgene",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Gene expression",
            "color": "black",
            "labels": [
                "Gene expression",
                "Expression",
                "Expr"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Gene_expression",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Neddylation",
            "color": "black",
            "labels": [
                "Neddylation",
                "Nedd"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Neddylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Place",
            "color": "black",
            "labels": [
                "Place"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Place-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Site",
            "color": "#0000aa",
            "labels": [
                "Site",
                "Si"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Site",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Instrument",
            "color": "black",
            "labels": [
                "Instrument",
                "Instr",
                "In"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Instrument",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Regulation",
            "color": "black",
            "labels": [
                "Regulation",
                "Reg"
            ],
            "unused": true,
            "bgColor": "#ffff00",
            "type": "Regulation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Acylation",
            "color": "black",
            "labels": [
                "Acylation",
                "Acyl"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Acylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein domain or region",
            "color": "black",
            "labels": [
                "Protein domain or region",
                "PDR"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "Protein_domain_or_region",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Cause",
            "color": "#007700",
            "labels": [
                "Cause",
                "Ca"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Cause",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Null Mutation",
            "color": "black",
            "labels": [
                "Null Mutation"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Null_Mutation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Gene or gene product",
            "color": "black",
            "labels": [
                "Gene or gene product",
                "GGP"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Gene_or_gene_product",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Multi-tissue structure",
            "color": "black",
            "labels": [
                "Multi-tissue structure",
                "Multi-t struct",
                "MT struct",
                "MT"
            ],
            "unused": true,
            "bgColor": "#e595ff",
            "type": "Multi_tissue_structure",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Depalmitoylation",
            "color": "black",
            "labels": [
                "Depalmitoylation",
                "-Palm"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Depalmitoylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Amino acid monomer",
            "color": "black",
            "labels": [
                "Amino acid monomer",
                "AA"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "Amino_acid_monomer",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Ubiquitination",
            "color": "black",
            "labels": [
                "Ubiquitination",
                "Ubiq"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Ubiquitination",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Methylation",
            "color": "black",
            "labels": [
                "Methylation",
                "Meth"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Methylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Two-component-system",
            "color": "black",
            "labels": [
                "Two-component-system",
                "2-comp-sys",
                "2CS"
            ],
            "unused": true,
            "bgColor": "#9999ff",
            "type": "Two-component-system",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "BV development",
            "color": "black",
            "labels": [
                "BV development",
                "BV devel"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Blood_vessel_development",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Org",
            "color": "black",
            "labels": [
                "Org"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Org-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Other_pharmaceutical_agent",
            "color": "black",
            "labels": [
                "Other_pharmaceutical_agent",
                "Other_pharm"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Other_pharmaceutical_agent",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deubiquitination",
            "color": "black",
            "labels": [
                "Deubiquitination",
                "-Ubiq"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deubiquitination",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Giver",
            "color": "black",
            "labels": [
                "Giver"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Giver-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "DNA methylation",
            "color": "black",
            "labels": [
                "DNA methylation",
                "DNA meth"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "DNA_methylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein-Component",
            "color": "#000077",
            "labels": [
                "Protein-Component",
                "Component",
                "Comp"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Protein-Component",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Beneficiary",
            "color": "black",
            "labels": [
                "Beneficiary"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Beneficiary-Arg",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "none",
            "name": "Equiv",
            "color": "black",
            "labels": [
                "Equiv",
                "Eq"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "dashArray": "3,3",
            "type": "Equiv",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Contextgene",
            "color": "#303030",
            "labels": [
                "Contextgene",
                "CGn"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Contextgene",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Confidence",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Confidence",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deneddylation",
            "color": "black",
            "labels": [
                "Deneddylation",
                "-Nedd"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deneddylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Two-component-system",
            "color": "black",
            "labels": [
                "Two-component-system",
                "2-comp-sys",
                "2CS"
            ],
            "unused": true,
            "bgColor": "#9999ff",
            "type": "Two-component-system",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Pathological formation",
            "color": "black",
            "labels": [
                "Pathological formation",
                "Pathological form",
                "Path form",
                "Path f",
                "PF"
            ],
            "unused": true,
            "bgColor": "#aaaaaa",
            "type": "Pathological_formation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein domain or region",
            "color": "black",
            "labels": [
                "Protein domain or region",
                "PDR"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "Protein_domain_or_region",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Cell_type",
            "color": "black",
            "labels": [
                "Cell_type",
                "Cell_t",
                "Cell"
            ],
            "unused": true,
            "bgColor": "#cf9fff",
            "type": "Cell_type",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein family or group",
            "color": "black",
            "labels": [
                "Protein family or group",
                "PFM"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Protein_family_or_group",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Chemical",
            "color": "black",
            "labels": [
                "Chemical",
                "Chem"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Chemical",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Regulon-operon",
            "color": "black",
            "labels": [
                "Regulon-operon",
                "Reg/op"
            ],
            "unused": true,
            "bgColor": "#9999ff",
            "type": "Regulon-operon",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Location",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#6fffdf",
            "type": "Location",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Vehicle",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#ccccee",
            "type": "Vehicle",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organism substance",
            "color": "black",
            "labels": [
                "Organism substance",
                "Ogn subst",
                "OSubst",
                "OSub",
                "OS"
            ],
            "unused": true,
            "bgColor": "#ffeee0",
            "type": "Organism_substance",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Catalysis",
            "color": "black",
            "labels": [
                "Catalysis",
                "Catal"
            ],
            "unused": true,
            "bgColor": "#e0ff00",
            "type": "Catalysis",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Cause",
            "color": "#007700",
            "labels": [
                "Cause",
                "Ca"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Cause",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "AtLoc",
            "color": "#0000cc",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "AtLoc",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Speculation",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "dashArray": "3,3",
            "type": "Speculation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Gene or gene product",
            "color": "black",
            "labels": [
                "Gene or gene product",
                "GGP"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Gene_or_gene_product",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Delipidation",
            "color": "black",
            "labels": [
                "Delipidation",
                "-Lipid"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Delipidation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "CSite",
            "color": "#0000aa",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "CSite",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Lipid",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#9fc2ff",
            "type": "Lipid",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Multi-tissue structure",
            "color": "black",
            "labels": [
                "Multi-tissue structure",
                "Multi-t struct",
                "MT struct",
                "MT"
            ],
            "unused": true,
            "bgColor": "#e595ff",
            "type": "Multi_tissue_structure",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Sub-Process",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#ee7a7a",
            "type": "Sub-Process",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Positive regulation",
            "color": "black",
            "labels": [
                "Positive regulation",
                "+Regulation",
                "+Reg"
            ],
            "unused": true,
            "bgColor": "#e0ff00",
            "type": "Positive_regulation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Virus",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#cf9fff",
            "type": "Virus",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Subcellular structure",
            "color": "black",
            "labels": [
                "Subcellular structure",
                "Subcell struct",
                "SubCell",
                "SCell",
                "SC"
            ],
            "unused": true,
            "bgColor": "#bbc3ff",
            "type": "Subcellular_structure",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Depalmitoylation",
            "color": "black",
            "labels": [
                "Depalmitoylation",
                "-Palm"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Depalmitoylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organic_compound_other",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#9fc2ff",
            "type": "Organic_compound_other",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "DNA_molecule",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "DNA_molecule",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein molecule",
            "color": "black",
            "labels": [
                "Protein molecule",
                "Prot.mol"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Protein_molecule",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Dealkylation",
            "color": "black",
            "labels": [
                "Dealkylation",
                "-Alkyl"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Dealkylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Amino acid monomer",
            "color": "black",
            "labels": [
                "Amino acid monomer",
                "AA"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "Amino_acid_monomer",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Desumoylation",
            "color": "black",
            "labels": [
                "Desumoylation",
                "-Sumo"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Desumoylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Drug or compound",
            "color": "black",
            "labels": [
                "Drug or compound",
                "Drug/comp",
                "D/C"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Drug_or_compound",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein_complex",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Protein_complex",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "DNA_family_or_group",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "DNA_family_or_group",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organ",
            "color": "black",
            "labels": [
                "Organ",
                "Ogn"
            ],
            "unused": true,
            "bgColor": "#e999ff",
            "type": "Organ",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Sidechain",
            "color": "#303030",
            "labels": [
                "Sidechain",
                "SCh"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Sidechain",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Weapon",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "darkgray",
            "type": "Weapon",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Site",
            "color": "#0000aa",
            "labels": [
                "Site",
                "Si"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Site",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deprenylation",
            "color": "black",
            "labels": [
                "Deprenylation",
                "-Prenyl"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deprenylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Polynucleotide",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "Polynucleotide",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deglycosylation",
            "color": "black",
            "labels": [
                "Deglycosylation",
                "-Glyc"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deglycosylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Negation",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Negation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Exp",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#cccccc",
            "type": "Exp",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Dephosphorylation",
            "color": "black",
            "labels": [
                "Dephosphorylation",
                "-Phos"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Dephosphorylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deacylation",
            "color": "black",
            "labels": [
                "Deacylation",
                "-Acyl"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deacylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Gene",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Gene",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Organism",
            "color": "black",
            "labels": [
                "Organism",
                "Org"
            ],
            "unused": true,
            "bgColor": "#ffccaa",
            "type": "Organism",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Cell_natural",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#cf9fff",
            "type": "Cell_natural",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Demethylation",
            "color": "black",
            "labels": [
                "Demethylation",
                "-Meth"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Demethylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "DNA demethylation",
            "color": "black",
            "labels": [
                "DNA demethylation",
                "DNA -meth"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "DNA_demethylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "ATTRIBUTE_DEFAULT",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "ATTRIBUTE_DEFAULT",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "DNA domain or region",
            "color": "black",
            "labels": [
                "DNA domain or region",
                "DDR"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "DNA_domain_or_region",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Other_pharmaceutical_agent",
            "color": "black",
            "labels": [
                "Other_pharmaceutical_agent",
                "Other_pharm"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Other_pharmaceutical_agent",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Facility",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#aaaaee",
            "type": "Facility",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deubiquitination",
            "color": "black",
            "labels": [
                "Deubiquitination",
                "-Ubiq"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deubiquitination",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "ARC_DEFAULT",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "ARC_DEFAULT",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "RNA_molecule",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "RNA_molecule",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Regulation",
            "color": "black",
            "labels": [
                "Regulation",
                "Reg"
            ],
            "unused": true,
            "bgColor": "#ffff00",
            "type": "Regulation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein-Component",
            "color": "#000077",
            "labels": [
                "Protein-Component",
                "Component",
                "Comp"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "type": "Protein-Component",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Entity",
            "color": "black",
            "labels": [
                "Entity",
                "Ent",
                "En",
                "E"
            ],
            "unused": true,
            "bgColor": "#b4c8ff",
            "type": "Entity",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Carbohydrate",
            "color": "black",
            "labels": [
                "Carbohydrate",
                "Carb"
            ],
            "unused": true,
            "bgColor": "#8fcfff",
            "type": "Carbohydrate",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Process",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#9fe67f",
            "type": "Process",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Tissue",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#cf9fff",
            "type": "Tissue",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Deacetylation",
            "color": "black",
            "labels": [
                "Deacetylation",
                "-Acet"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Deacetylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "ToLoc",
            "color": "#0000cc",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "ToLoc",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Money",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#007000",
            "type": "Money",
            "fgColor": "white"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Dehydroxylation",
            "color": "black",
            "labels": [
                "Dehydroxylation",
                "-Hydr"
            ],
            "unused": true,
            "bgColor": "#18c59a",
            "type": "Dehydroxylation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Protein",
            "color": "black",
            "labels": [
                "Protein",
                "Pro",
                "Pr",
                "P"
            ],
            "unused": true,
            "bgColor": "#7fa2ff",
            "type": "Protein",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Negative regulation",
            "color": "black",
            "labels": [
                "Negative regulation",
                "-Regulation",
                "-Reg"
            ],
            "unused": true,
            "bgColor": "#ffe000",
            "type": "Negative_regulation",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Cell_cultured",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#cf9fff",
            "type": "Cell_cultured",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "Experimental_method",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "#ffff00",
            "type": "Experimental_method",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "triangle,5",
            "name": "SPAN_DEFAULT",
            "color": "black",
            "labels": null,
            "unused": true,
            "bgColor": "lightgreen",
            "type": "SPAN_DEFAULT",
            "fgColor": "black"
        },
        {
            "borderColor": "darken",
            "arrowHead": "none",
            "name": "Equiv",
            "color": "black",
            "labels": [
                "Equiv",
                "Eq"
            ],
            "unused": true,
            "bgColor": "lightgreen",
            "dashArray": "3,3",
            "type": "Equiv",
            "fgColor": "black"
        }
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

var dummyDocEdited = {
    "protocol": 1,
    "messages": [],
    "undo": "{\"action\": \"add_tb\", \"attributes\": \"{}\", \"normalizations\": \"[]\", \"id\": \"T1\"}",
    "action": "createSpan",
    "annotations": {
        "modifications": [],
        "normalizations": [],
        "ctime": 1651329402.441729,
        "triggers": [],
        "text": "Top bar and menu\n\nAs you may have already noticed, brat has a blue TOP BAR that opens the MENU when you place the mouse cursor over it. Once the mouse pointer leaves the menu area the menu will disappear, allowing more screen space for text and annotations.\n\nThe menu provides access to various dialogs for working with text and annotations, as well as a button for logging in in and out of brat.\n\nFinally, you should find a \"help\" link in the menu, which will open the brat manual in a separate page if clicked. You won't need the manual for this tutorial, but it may be helpful for reference later.\n",
        "source_files": [
            "ann",
            "txt"
        ],
        "mtime": 1651329528.087105,
        "sentence_offsets": [
            [
                0,
                16
            ],
            [
                18,
                257
            ],
            [
                259,
                396
            ],
            [
                398,
                600
            ]
        ],
        "relations": [],
        "entities": [

        ],
        "comments": [],
        "token_offsets": [
            [
                0,
                3
            ],
            [
                4,
                7
            ],
            [
                8,
                11
            ],
            [
                12,
                16
            ],
            [
                18,
                20
            ],
            [
                21,
                24
            ],
            [
                25,
                28
            ],
            [
                29,
                33
            ],
            [
                34,
                41
            ],
            [
                42,
                50
            ],
            [
                51,
                55
            ],
            [
                56,
                59
            ],
            [
                60,
                61
            ],
            [
                62,
                66
            ],
            [
                67,
                70
            ],
            [
                71,
                74
            ],
            [
                75,
                79
            ],
            [
                80,
                85
            ],
            [
                86,
                89
            ],
            [
                90,
                94
            ],
            [
                95,
                99
            ],
            [
                100,
                103
            ],
            [
                104,
                109
            ],
            [
                110,
                113
            ],
            [
                114,
                119
            ],
            [
                120,
                126
            ],
            [
                127,
                131
            ],
            [
                132,
                135
            ],
            [
                136,
                140
            ],
            [
                141,
                144
            ],
            [
                145,
                150
            ],
            [
                151,
                158
            ],
            [
                159,
                165
            ],
            [
                166,
                169
            ],
            [
                170,
                174
            ],
            [
                175,
                179
            ],
            [
                180,
                183
            ],
            [
                184,
                188
            ],
            [
                189,
                193
            ],
            [
                194,
                204
            ],
            [
                205,
                213
            ],
            [
                214,
                218
            ],
            [
                219,
                225
            ],
            [
                226,
                231
            ],
            [
                232,
                235
            ],
            [
                236,
                240
            ],
            [
                241,
                244
            ],
            [
                245,
                257
            ],
            [
                259,
                262
            ],
            [
                263,
                267
            ],
            [
                268,
                276
            ],
            [
                277,
                283
            ],
            [
                284,
                286
            ],
            [
                287,
                294
            ],
            [
                295,
                302
            ],
            [
                303,
                306
            ],
            [
                307,
                314
            ],
            [
                315,
                319
            ],
            [
                320,
                324
            ],
            [
                325,
                328
            ],
            [
                329,
                341
            ],
            [
                342,
                344
            ],
            [
                345,
                349
            ],
            [
                350,
                352
            ],
            [
                353,
                354
            ],
            [
                355,
                361
            ],
            [
                362,
                365
            ],
            [
                366,
                373
            ],
            [
                374,
                376
            ],
            [
                377,
                379
            ],
            [
                380,
                383
            ],
            [
                384,
                387
            ],
            [
                388,
                390
            ],
            [
                391,
                396
            ],
            [
                398,
                406
            ],
            [
                407,
                410
            ],
            [
                411,
                417
            ],
            [
                418,
                422
            ],
            [
                423,
                424
            ],
            [
                425,
                431
            ],
            [
                432,
                436
            ],
            [
                437,
                439
            ],
            [
                440,
                443
            ],
            [
                444,
                449
            ],
            [
                450,
                455
            ],
            [
                456,
                460
            ],
            [
                461,
                465
            ],
            [
                466,
                469
            ],
            [
                470,
                474
            ],
            [
                475,
                481
            ],
            [
                482,
                484
            ],
            [
                485,
                486
            ],
            [
                487,
                495
            ],
            [
                496,
                500
            ],
            [
                501,
                503
            ],
            [
                504,
                512
            ],
            [
                513,
                516
            ],
            [
                517,
                522
            ],
            [
                523,
                527
            ],
            [
                528,
                531
            ],
            [
                532,
                538
            ],
            [
                539,
                542
            ],
            [
                543,
                547
            ],
            [
                548,
                557
            ],
            [
                558,
                561
            ],
            [
                562,
                564
            ],
            [
                565,
                568
            ],
            [
                569,
                571
            ],
            [
                572,
                579
            ],
            [
                580,
                583
            ],
            [
                584,
                593
            ],
            [
                594,
                600
            ]
        ],
        "attributes": [],
        "equivs": [],
        "events": []
    }
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
            if (data.action === "getCollectionInformation") {
                dispatcher.post('messages', [dummyReturn.messages]);
                dispatcher.post(0, callback, [dummyReturn]);
                return dummyReturn;
            }
            else if (data.action === "whoami") {
                // dont show the tut
                dispatcher.post('messages', [whoamiResponse.messages]);
                dispatcher.post(0, callback, [whoamiResponse]);
                return whoamiResponse;
            }
            else if (data.action === "createSpan") {

                let loadedDoc = window.loadedDoc
                if (!loadedDoc.annotations) {
                    loadedDoc.annotations = loadedDoc
                    loadedDoc.annotations.entities = []
                }
                loadedDoc.annotations.entities.push([loadedDoc.annotations.entities.length + 1, data.type, JSON.parse(data.offsets)])
                loadedDoc.edited = ["1"]

                dispatcher.post('messages', [loadedDoc.messages]);
                dispatcher.post(0, callback, [loadedDoc]);
                // dummyDocEdited.annotations.entities.push([dummyDocEdited.annotations.entities.length + 1, data.type, JSON.parse(data.offsets)])
                // dummyDocEdited.edited = ["1"]
                //
                // dispatcher.post('messages', [dummyDocEdited.messages]);
                // dispatcher.post(0, callback, [dummyDocEdited]);

                return data;
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
            setTimeout(() =>
                window.ajaxCallback(options),
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
