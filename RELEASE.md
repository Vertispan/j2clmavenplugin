# Making a release of j2cl-maven-plugin

## Building the project

At this time we're preferring a manual approach to the release, but may automate this further in the future. 

For a new major or minor release, create a new release branch, otherwise check out the existing branch:
```
git checkout -b release/x.y
```
or
```
git checkout release/x.y
```

Update the pom files to reflect the new version 
```
mvn versions:set -DnewVersion=x.y.z
```
Make a commit with these changes to the release branch
```
git commit -a -m "Release x.y.z"
```
and tag it
```
git tag vx.y.z
```
Do not push these yet.

Next, deploy to Sonatype staging
```
mvn deploy -Prelease
```

Log in to sonatype and close the staging repo, but do not yet release it.

Add the closed staging repo to other projects and confirm they build as expected, that the release is not somehow
broken already. Ideally the `deploy` above would catch all bugs, but real life use cases always have extra complexity,
and this is the last chance to catch an issue.

Once all acceptance testing has passed, release the sonatype repo to being sync to central using the Sonatype console.

Finally, push the release branch and tag it
```
git push upstream vx.y.z release/x.y
```

To continue development, create a pull request to likewise change the versions of the poms in the project to the next
anticipated release, and update any version references to point to this release.

## Generating site documentation

Presently we are only interested in deploying the documentation for j2cl-maven-plugin itself. First, cd into that 
directory:

```
cd j2cl-maven-plugin
```

Build the project, including its documentation

```
mvn verify site
```

or if the project is already built, just the documentation

```
mvn site
```

If this passes and the [results](target/site) appear to be correct at a glance, deploy them with

```
mvn site:stage scm-publish:publish-scm
```