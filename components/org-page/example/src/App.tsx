import React from 'react'

import Tab, { ProjectProps, MemberProps } from 'react-tab'
import 'react-tab/dist/index.css'

const projects: ProjectProps[] = [
  {
    status: 'PAUSED/COMPLETE',
    title: 'Cholestasis Title/Abstract Screen',
    isPublic: true,
    description: 'Et inceptos justo in vel dolor natoque, in. Quisque nunc in, risus posuere. Integer eu. Consequat in dui eu dolor senectus velit fusce, id. Parturient, mus quisque, tristique pellentesque gravida fermentum turpis. Ut egestas orci et sagittis et habitasse ipsum sem turpis non. Non leo pellentesque, ut bibendum quam vivamus eget ipsum, pretium. Litora magnis suscipit ultricies est ut dis in elementum ultrices at. Pharetra, eros tortor arcu magna nunc duis.',
    members: 10, projectId: '77703',
    link: 'https://sysrev.com/p/77703',
    createdAt: '2021-07-14 12:28:13', lastAtive: '2022-01-10 11:10:52',
    pinned: false
  },
  {
    status: 'PAUSED',
    title: 'Master Screening Labels',
    description: 'Sed nibh mi fermentum sit felis, nisi, donec. Porttitor tincidunt sem a ut donec, mattis commodo cubilia, non maecenas. Tristique parturient sed vitae turpis eu hendrerit ligula velit. Netus eleifend consequat, adipiscing venenatis, nam dui eget. Quis phasellus suspendisse sed lacinia consequat ex laoreet. Auctor nulla vehicula dignissim. Aenean est et interdum amet suspendisse urna dis maecenas sociosqu tristique dolor amet risus non a per. Efficitur duis accumsan sociis mauris vitae sapien non? In turpis nam viverra dui efficitur ut, ullamcorper sapien est ullamcorper scelerisque himenaeos viverra varius. Proin condimentum egestas eget eu tortor enim leo. Taciti senectus feugiat eget, convallis vel.',
    members: 2, projectId: '90507',
    link: 'https://sysrev.com/p/90507',
    createdAt: '2021-09-24 16:43:34', lastAtive: '2022-01-10 11:10:52',
    pinned: true
  },
  {
    status: 'PAUSED',
    title: 'ONTOX Exploration - Screen & Sort',
    isPublic: true,
    description: 'Inceptos sed, ex sem nec sed interdum eu dictumst. Pulvinar iaculis lacus aliquam ante in? Luctus arcu condimentum suspendisse pharetra lacus sed. Convallis et varius nostra. Turpis mauris congue rhoncus vel maximus curae a, ut, egestas nullam tempor. Vel facilisi, interdum adipiscing netus, in dolor sed in netus sed sed mus ultrices. Orci nulla lacinia justo molestie feugiat dis urna, et, semper, donec ut. Et facilisis ridiculus in elit.',
    members: 10, projectId: '77703',
    link: 'https://sysrev.com/p/77703',
    createdAt: '2021-07-14 12:28:13', lastAtive: '2022-01-10 11:10:52'
  },
  {
    status: 'PAUSED',
    title: 'Master Screening Labels',
    isPublic: true,
    description: 'Sed nibh mi fermentum sit felis, nisi, donec. Porttitor tincidunt sem a ut donec, mattis commodo cubilia, non maecenas. Tristique parturient sed vitae turpis eu hendrerit ligula velit. Netus eleifend consequat, adipiscing venenatis, nam dui eget. Quis phasellus suspendisse sed lacinia consequat ex laoreet. Auctor nulla vehicula dignissim. Aenean est et interdum amet suspendisse urna dis maecenas sociosqu tristique dolor amet risus non a per. Efficitur duis accumsan sociis mauris vitae sapien non? In turpis nam viverra dui efficitur ut, ullamcorper sapien est ullamcorper scelerisque himenaeos viverra varius. Proin condimentum egestas eget eu tortor enim leo. Taciti senectus feugiat eget, convallis vel.',
    members: 2, projectId: '90507',
    link: 'https://sysrev.com/p/90507',
    createdAt: '2021-09-24 16:43:34', lastAtive: '2022-01-10 11:10:52',
    pinned: true
  },
  {
    status: 'PAUSED/COMPLETE',
    title: 'Cholestasis Title/Abstract Screen',
    isPublic: true,
    description: 'Et inceptos justo in vel dolor natoque, in. Quisque nunc in, risus posuere. Integer eu. Consequat in dui eu dolor senectus velit fusce, id. Parturient, mus quisque, tristique pellentesque gravida fermentum turpis. Ut egestas orci et sagittis et habitasse ipsum sem turpis non. Non leo pellentesque, ut bibendum quam vivamus eget ipsum, pretium. Litora magnis suscipit ultricies est ut dis in elementum ultrices at. Pharetra, eros tortor arcu magna nunc duis.',
    members: 10, projectId: '77703',
    link: 'https://sysrev.com/p/77703',
    createdAt: '2021-07-14 12:28:13', lastAtive: '2022-01-10 11:10:52'
  },
  {
    status: 'PAUSED',
    title: 'Master Screening Labels',
    isPublic: true,
    description: 'Sed nibh mi fermentum sit felis, nisi, donec. Porttitor tincidunt sem a ut donec, mattis commodo cubilia, non maecenas. Tristique parturient sed vitae turpis eu hendrerit ligula velit. Netus eleifend consequat, adipiscing venenatis, nam dui eget. Quis phasellus suspendisse sed lacinia consequat ex laoreet. Auctor nulla vehicula dignissim. Aenean est et interdum amet suspendisse urna dis maecenas sociosqu tristique dolor amet risus non a per. Efficitur duis accumsan sociis mauris vitae sapien non? In turpis nam viverra dui efficitur ut, ullamcorper sapien est ullamcorper scelerisque himenaeos viverra varius. Proin condimentum egestas eget eu tortor enim leo. Taciti senectus feugiat eget, convallis vel.',
    members: 2, projectId: '90507',
    link: 'https://sysrev.com/p/90507',
    createdAt: '2021-09-24 16:43:34', lastAtive: '2022-01-10 11:10:52',
    pinned: true
  }
]

const members: MemberProps[] = [
  { name: 'Sadeeqa', userId: 6058, url: 'https://sysrev.com/user/6058/profile'},
  { name: 'Shahla', userId: 6306, url: 'https://sysrev.com/user/6058/profile'},
  { name: 'Nakheel', userId: 5829, url: 'https://sysrev.com/user/6058/profile'},
  { name: 'Imtinaan', userId: 4990, url: 'https://sysrev.com/user/6058/profile'},
  { name: 'Sadeeqa', userId: 6058, url: 'https://sysrev.com/user/6058/profile'},
  { name: 'Shahla', userId: 6306, url: 'https://sysrev.com/user/6058/profile'},
  { name: 'Nakheel', userId: 5829, url: 'https://sysrev.com/user/6058/profile'},
  { name: 'Imtinaan', userId: 4990, url: 'https://sysrev.com/user/6058/profile'},
]

const App = () => {
  return <Tab
    projects={projects}
    members={members}
  />
}

export default App
