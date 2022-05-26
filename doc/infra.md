# Infrastructure

Infrastructure is deployed by [sysrev.infra.core](../components/infra/src/sysrev/infra/core.clj). Configuration is set in cloudformation-config.edn: [Dev](../config/dev/cloudformation-config.edn) | [Prod](../config/prod/cloudformation-config.edn). A few secret or dynamic values are set in enviroment variables.

CloudFormation stacks are used to manage the infra state. The `system-map` provides a top-level view of the references between them. All of the references and dependencies should be listed explicitly. E.g., `:CodeBucket` is an output of the `Sysrev-Global-Resources` stack that is a parameter for the `Sysrev-Regional-Resources` and `Sysrev-GraphQL-Gateway` stacks.

References:
- [system](https://github.com/donut-power/system) handles state management and references between components.
- [salmon](https://github.com/john-shaffer/salmon) manages the CloudFormation stacks.
- [cloudformation-templating](https://github.com/staticweb-io/cloudformation-templating) provides helpers for defining the CloudFormation templates.
- The [AWS CloudFormation User Guide](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/Welcome.html) is the definitive reference for CloudFormation templates.

## Example: Adding a DNS Record

1. Start a REPL with AWS keys for the test account. [aws-vault](https://github.com/99designs/aws-vault) is a good way to set AWS credentials as environment variables that the deployer can pick up.
1. Find [`:global-resources`](https://github.com/insilica/systematic_review/blob/96e3a2b67f830bedac64087c9770ab40abd341e5/components/infra/src/sysrev/infra/core.clj#L44-L49) in `sysrev.infra.core`
1. Use your editor to jump to the definition of [`global/template`](https://github.com/insilica/systematic_review/blob/96e3a2b67f830bedac64087c9770ab40abd341e5/components/infra/src/sysrev/infra/global.clj#L46)
1. Find [`:DatapubRecordSetGroup`](https://github.com/insilica/systematic_review/blob/96e3a2b67f830bedac64087c9770ab40abd341e5/components/infra/src/sysrev/infra/global.clj#L71) in the template
1. Add a new `A` record for an `example` subdomain:
```
{:Name (join "." ["example" (ref :DatapubZoneApex)])
 :ResourceRecords ["54.210.22.161"]
 :TTL "900"
 :Type "A"}
```
1. Execute `(sysrev.infra.core/deploy! {:groups [:common]})` in your REPL.
1. After a minute or two, the command should complete and the new record should exist:
```
$ host example.datapubdev.net
example.datapubdev.net has address 54.210.22.161
```
1. When you are done testing a change, commit it. When you push to the staging or production branch, [cloudformation.yml](../.github/workflows/cloudformation.yml) will deploy your changes.
