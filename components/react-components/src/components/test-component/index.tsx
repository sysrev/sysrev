import * as React from 'react'
import { createUseStyles } from 'react-jss'


 const useStyles = createUseStyles({
   testStyle: {
     color: 'green'
   }
 })

 const TestComponent = () => {
   const classes = useStyles();
   return (
     <div className={classes.testStyle}>
       <h1>Test Component Working!</h1>
     </div>
   )
 }

 export default TestComponent
