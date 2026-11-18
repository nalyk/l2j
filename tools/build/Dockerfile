# Living Chronicle build image.
#
# The pinned JDK plus Ant and the test libraries, each verified against
# toolchain.lock while the image is assembled. No package manager runs, so
# nothing unpinned can enter. Built by ./build.sh; never built by hand.

ARG JDK_IMAGE
FROM ${JDK_IMAGE}

COPY toolchain.lock tools/build/Provision.java /opt/lc/

RUN java /opt/lc/Provision.java /opt/lc/toolchain.lock /opt/lc \
 && mkdir /opt/ant \
 && tar -xzf /opt/lc/files/ant.tar.gz -C /opt/ant --strip-components=1 \
 && rm -r /opt/lc/files

ENV ANT_HOME=/opt/ant
ENV PATH=/opt/ant/bin:${PATH}
